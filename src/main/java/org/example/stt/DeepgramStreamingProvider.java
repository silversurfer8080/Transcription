package org.example.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streams PCM audio to Deepgram's real-time transcription API via WebSocket.
 *
 * <h3>Threading model</h3>
 * <p>A dedicated sender virtual thread drains an internal queue and forwards
 * binary frames to the WebSocket. This decouples the audio capture thread from
 * network I/O and prevents the {@link IllegalStateException} that the Java
 * WebSocket API throws when a second {@code sendBinary()} is called before the
 * first completes. The queue holds up to 64 chunks (~6.4 s); older chunks are
 * dropped with a warning if the network can't keep up.
 *
 * <h3>Deepgram parameters</h3>
 * <p>Uses {@code nova-2-general} with {@code punctuate=true},
 * {@code interim_results=true}, and {@code smart_format=true}. The sample rate
 * and channel count are derived from the {@link AudioFormat} passed to
 * {@link #start}.
 */
public class DeepgramStreamingProvider implements SpeechToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepgramStreamingProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WS_BASE = "wss://api.deepgram.com/v1/listen";

    private final String apiKey;
    private final String channelId;

    private HttpClient httpClient;
    private WebSocket webSocket;
    private Consumer<TranscriptEvent> onResult;
    private Thread senderThread;
    private final LinkedBlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<>(64);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DeepgramStreamingProvider(String apiKey, String channelId) {
        this.apiKey = apiKey;
        this.channelId = channelId;
    }

    @Override
    public void start(AudioFormat format, Consumer<TranscriptEvent> onResult) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("DeepgramStreamingProvider already started");
        }
        this.onResult = onResult;

        String url = buildUrl(format);
        log.info("Connecting to Deepgram (channel={}): {}", channelId, url);

        httpClient = HttpClient.newHttpClient();
        webSocket = httpClient.newWebSocketBuilder()
                .header("Authorization", "Token " + apiKey)
                .buildAsync(URI.create(url), new DeepgramListener())
                .get(10, TimeUnit.SECONDS);

        senderThread = Thread.ofVirtual()
                .name("deepgram-sender-" + channelId)
                .start(this::sendLoop);

        log.info("Connected to Deepgram (channel={})", channelId);
    }

    @Override
    public void sendAudioChunk(byte[] pcmData) {
        if (!running.get()) return;
        if (!sendQueue.offer(pcmData)) {
            log.warn("Deepgram send queue full — dropping chunk (channel={})", channelId);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        // Wake the sender thread so it exits its poll loop
        if (senderThread != null) {
            senderThread.interrupt();
            try {
                senderThread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (webSocket != null) {
                webSocket.sendText("{\"type\":\"CloseStream\"}", true)
                         .get(3, TimeUnit.SECONDS);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "session ended")
                         .get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // Avoid logging raw exception messages that may contain HTTP response
            // headers or auth context from the WebSocket handshake.
            log.warn("Error closing Deepgram WebSocket (channel={}): {}", channelId,
                    e.getClass().getSimpleName());
        } finally {
            if (httpClient != null) httpClient.close();
        }

        log.info("Deepgram provider stopped (channel={})", channelId);
    }

    // ---- sender loop (virtual thread) ------------------------------------

    private void sendLoop() {
        while (running.get()) {
            try {
                byte[] chunk = sendQueue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                // join() parks this virtual thread until the send completes —
                // safe and correct because virtual threads are cheap to park.
                webSocket.sendBinary(ByteBuffer.wrap(chunk), true).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Deepgram send error (channel={})", channelId, e);
                }
            }
        }
    }

    // ---- WebSocket listener ----------------------------------------------

    private class DeepgramListener implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            log.debug("Deepgram WebSocket open (channel={})", channelId);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                handleMessage(buf.toString());
                buf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.info("Deepgram WebSocket closed: status={} reason='{}' (channel={})",
                    statusCode, reason, channelId);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("Deepgram WebSocket error (channel={})", channelId, error);
        }
    }

    // ---- JSON parsing ----------------------------------------------------

    private void handleMessage(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String type = root.path("type").asText();

            if (!"Results".equals(type)) {
                log.debug("Deepgram message type='{}' (channel={})", type, channelId);
                return;
            }

            JsonNode alternatives = root.path("channel").path("alternatives");
            if (alternatives.isEmpty()) return;

            String text = alternatives.get(0).path("transcript").asText("").trim();
            if (text.isEmpty()) return;

            boolean isFinal = root.path("is_final").asBoolean(false);
            double confidence = alternatives.get(0).path("confidence").asDouble(-1);

            onResult.accept(new TranscriptEvent(text, isFinal, confidence, channelId));

        } catch (Exception e) {
            log.error("Failed to parse Deepgram message", e);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static String buildUrl(AudioFormat fmt) {
        return WS_BASE
                + "?model=nova-2-general"
                + "&language=en"
                + "&encoding=linear16"
                + "&sample_rate=" + (int) fmt.getSampleRate()
                + "&channels=" + fmt.getChannels()
                + "&punctuate=true"
                + "&interim_results=true"
                + "&smart_format=true";
    }
}
