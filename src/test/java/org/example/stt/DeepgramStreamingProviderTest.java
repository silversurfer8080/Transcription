package org.example.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;

import static org.junit.jupiter.api.Assertions.*;

class DeepgramStreamingProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AudioFormat FORMAT_16KHZ_MONO = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, false);

    // ── extractTranscriptEvent — positive paths ───────────────────────────────

    @Test
    void extractTranscriptEvent_finalResult_returnsFullyPopulatedEvent() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {
                    "alternatives": [
                      {"transcript": "hello world", "confidence": 0.98}
                    ]
                  },
                  "is_final": true
                }
                """);
        TranscriptEvent event = DeepgramStreamingProvider.extractTranscriptEvent(root, "mic");

        assertNotNull(event);
        assertEquals("hello world", event.text());
        assertTrue(event.isFinal());
        assertEquals(0.98, event.confidence(), 1e-6);
        assertEquals("mic", event.channelId());
    }

    @Test
    void extractTranscriptEvent_partialResult_isFinalIsFalse() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {
                    "alternatives": [{"transcript": "hel", "confidence": 0.8}]
                  },
                  "is_final": false
                }
                """);
        TranscriptEvent event = DeepgramStreamingProvider.extractTranscriptEvent(root, "candidate");
        assertNotNull(event);
        assertFalse(event.isFinal());
        assertEquals("candidate", event.channelId());
    }

    @Test
    void extractTranscriptEvent_missingConfidenceField_defaultsToNegativeOne() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {"alternatives": [{"transcript": "hello"}]},
                  "is_final": true
                }
                """);
        TranscriptEvent event = DeepgramStreamingProvider.extractTranscriptEvent(root, "mic");
        assertNotNull(event);
        assertEquals(-1.0, event.confidence(), 1e-9, "Missing confidence must default to -1");
    }

    @Test
    void extractTranscriptEvent_leadingTrailingWhitespace_isTrimmed() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {"alternatives": [{"transcript": "  hello  ", "confidence": 0.9}]},
                  "is_final": true
                }
                """);
        TranscriptEvent event = DeepgramStreamingProvider.extractTranscriptEvent(root, "mic");
        assertNotNull(event);
        assertEquals("hello", event.text(), "Transcript must be trimmed");
    }

    // ── extractTranscriptEvent — null / empty cases ───────────────────────────

    @Test
    void extractTranscriptEvent_blankTranscript_returnsNull() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {"alternatives": [{"transcript": "   ", "confidence": 0.0}]},
                  "is_final": false
                }
                """);
        assertNull(DeepgramStreamingProvider.extractTranscriptEvent(root, "mic"),
                "Blank transcript must be silently ignored");
    }

    @Test
    void extractTranscriptEvent_emptyAlternatives_returnsNull() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {"alternatives": []},
                  "is_final": false
                }
                """);
        assertNull(DeepgramStreamingProvider.extractTranscriptEvent(root, "mic"));
    }

    @Test
    void extractTranscriptEvent_emptyTranscriptString_returnsNull() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "type": "Results",
                  "channel": {"alternatives": [{"transcript": "", "confidence": 0.0}]},
                  "is_final": false
                }
                """);
        assertNull(DeepgramStreamingProvider.extractTranscriptEvent(root, "mic"));
    }

    // ── buildUrl ──────────────────────────────────────────────────────────────

    @Test
    void buildUrl_usesWssScheme() {
        assertTrue(DeepgramStreamingProvider.buildUrl(FORMAT_16KHZ_MONO).startsWith("wss://"),
                "Deepgram WebSocket URL must use wss:// scheme");
    }

    @Test
    void buildUrl_includesSampleRate() {
        String url = DeepgramStreamingProvider.buildUrl(FORMAT_16KHZ_MONO);
        assertTrue(url.contains("sample_rate=16000"));
    }

    @Test
    void buildUrl_includesMonoChannelCount() {
        String url = DeepgramStreamingProvider.buildUrl(FORMAT_16KHZ_MONO);
        assertTrue(url.contains("channels=1"));
    }

    @Test
    void buildUrl_stereoFormat_reflectsChannelCount() {
        var stereo = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 48_000f, 16, 2, 4, 48_000f, false);
        String url = DeepgramStreamingProvider.buildUrl(stereo);
        assertTrue(url.contains("channels=2"));
        assertTrue(url.contains("sample_rate=48000"));
    }

    // ── Public API — safe pre-start behavior ──────────────────────────────────

    @Test
    void sendAudioChunk_beforeStart_dropsChunkSilently() {
        var provider = new DeepgramStreamingProvider("key", "test");
        // running=false means the chunk should be ignored without throwing
        assertDoesNotThrow(() -> provider.sendAudioChunk(new byte[3200]));
    }

    @Test
    void stop_beforeStart_isIdempotentAndDoesNotThrow() {
        var provider = new DeepgramStreamingProvider("key", "test");
        assertDoesNotThrow(provider::stop);
    }

    @Test
    void stop_calledTwice_doesNotThrow() {
        var provider = new DeepgramStreamingProvider("key", "test");
        provider.stop();
        assertDoesNotThrow(provider::stop);
    }
}
