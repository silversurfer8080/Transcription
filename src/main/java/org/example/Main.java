package org.example;

import org.example.audio.AudioCapture;
import org.example.audio.AudioDeviceInfo;
import org.example.audio.AudioDevices;
import org.example.audio.WavFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Phase-1 smoke test (CLI). Lists capture devices, records 10 seconds from
 * a chosen device, and writes the result to {@code test-recording.wav}.
 *
 * <p>The JavaFX UI lands in Phase 2; for now this entry point exists purely
 * to verify the audio capture + WAV writing pipeline end-to-end.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew run                  # interactive: prints devices, prompts for index
 *   ./gradlew run --args "3"       # non-interactive: device index 3
 * </pre>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * Project-wide default capture format.
     *
     * <p>16 kHz / 16-bit signed / mono / little-endian: the canonical input
     * shape for Deepgram, AssemblyAI, Vosk and whisper.cpp. Anything coarser
     * gets rejected by the streaming endpoints; anything finer is wasted
     * bandwidth on a voice signal that tops out around 8 kHz of useful
     * spectrum anyway.
     */
    public static final AudioFormat DEFAULT_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            16_000f,   // sample rate (Hz)
            16,        // sample size (bits)
            1,         // channels (mono)
            2,         // frame size (bytes) = channels * bits/8
            16_000f,   // frame rate
            false      // bigEndian = false (LE — what WAV expects)
    );

    private static final int CAPTURE_SECONDS = 10;
    private static final String OUTPUT_FILE = "test-recording.wav";

    public static void main(String[] args) throws Exception {
        List<AudioDeviceInfo> devices = AudioDevices.listCaptureDevices();
        if (devices.isEmpty()) {
            System.err.println("No capture-capable audio devices found.");
            System.exit(1);
        }

        System.out.println("Capture devices:");
        for (AudioDeviceInfo d : devices) {
            System.out.println("  " + d);
        }

        int chosen = chooseDeviceIndex(args, devices.size());
        AudioDeviceInfo dev = devices.get(chosen);
        System.out.println();
        System.out.println("Using: " + dev);
        System.out.println("Format: " + DEFAULT_FORMAT);

        Path output = Paths.get(OUTPUT_FILE).toAbsolutePath();

        // try-with-resources order matters: AudioCapture is declared second so
        // it closes (= stops capture, joins reader thread) BEFORE the writer
        // closes — that way the last in-flight chunk is flushed before we
        // patch the WAV header.
        try (WavFileWriter writer = new WavFileWriter(output, DEFAULT_FORMAT);
             AudioCapture capture = new AudioCapture(
                     dev.mixerInfo(),
                     DEFAULT_FORMAT,
                     chunk -> {
                         try {
                             writer.write(chunk);
                         } catch (Exception e) {
                             log.error("Failed to write chunk", e);
                         }
                     })) {

            System.out.println("Recording for " + CAPTURE_SECONDS + " seconds...");
            capture.start();
            Thread.sleep(CAPTURE_SECONDS * 1000L);
            capture.stop();

            System.out.println("Captured " + writer.dataBytesWritten() + " bytes of PCM.");
        }

        System.out.println();
        System.out.println("Saved: " + output);
    }

    private static int chooseDeviceIndex(String[] args, int deviceCount) throws Exception {
        if (args.length > 0) {
            int idx = Integer.parseInt(args[0].trim());
            requireInRange(idx, deviceCount);
            return idx;
        }
        System.out.print("\nChoose device index: ");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line = reader.readLine();
        if (line == null) {
            throw new IllegalStateException("No input received for device selection");
        }
        int idx = Integer.parseInt(line.trim());
        requireInRange(idx, deviceCount);
        return idx;
    }

    private static void requireInRange(int idx, int count) {
        if (idx < 0 || idx >= count) {
            throw new IllegalArgumentException(
                    "Device index " + idx + " out of range [0, " + (count - 1) + "]");
        }
    }
}
