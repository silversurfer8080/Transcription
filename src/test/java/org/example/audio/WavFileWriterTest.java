package org.example.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WavFileWriterTest {

    private static final AudioFormat PCM_16KHZ_MONO = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, false);

    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile("wav-test-", ".wav");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempFile);
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void constructor_writes44BytePlaceholderHeader() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            assertEquals(44L, Files.size(tempFile));
        }
    }

    @Test
    void path_returnsConfiguredPath() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            assertEquals(tempFile, writer.path());
        }
    }

    // ── Write tracking ────────────────────────────────────────────────────────

    @Test
    void write_tracksBytesWritten() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            writer.write(new byte[3200]);
            assertEquals(3200L, writer.dataBytesWritten());
        }
    }

    @Test
    void write_multipleChunks_accumulatesCount() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            writer.write(new byte[1600]);
            writer.write(new byte[800]);
            assertEquals(2400L, writer.dataBytesWritten());
        }
    }

    @Test
    void write_withOffsetAndLength_tracksOnlySubrangeBytes() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            writer.write(new byte[200], 10, 50);
            assertEquals(50L, writer.dataBytesWritten());
        }
    }

    @Test
    void write_emptyArray_doesNotChangeByteCount() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            writer.write(new byte[0]);
            assertEquals(0L, writer.dataBytesWritten());
        }
    }

    @Test
    void write_afterClose_throwsIOException() throws IOException {
        var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO);
        writer.close();
        assertThrows(IOException.class, () -> writer.write(new byte[10]));
    }

    // ── Header patching ───────────────────────────────────────────────────────

    @Test
    void close_patchesRiffMarker() throws IOException {
        writeAndClose(3200);
        assertEquals("RIFF", headerChunkId(0));
    }

    @Test
    void close_patchesWaveMarker() throws IOException {
        writeAndClose(3200);
        assertEquals("WAVE", headerChunkId(8));
    }

    @Test
    void close_patchesFmtSubchunkId() throws IOException {
        writeAndClose(3200);
        assertEquals("fmt ", headerChunkId(12));
    }

    @Test
    void close_patchesDataSubchunkId() throws IOException {
        writeAndClose(3200);
        assertEquals("data", headerChunkId(36));
    }

    @Test
    void close_patchesRiffChunkSize() throws IOException {
        int dataBytes = 3200;
        writeAndClose(dataBytes);
        // RIFF chunk size = (file size - 8) = (44 + dataBytes - 8) = 36 + dataBytes
        assertEquals(36 + dataBytes, readIntLE(4));
    }

    @Test
    void close_patchesFmtChunkSize_is16ForPcm() throws IOException {
        writeAndClose(100);
        assertEquals(16, readIntLE(16));
    }

    @Test
    void close_patchesPcmAudioFormatCode_is1() throws IOException {
        writeAndClose(100);
        assertEquals(1, readShortLE(20));
    }

    @Test
    void close_patchesChannelCount() throws IOException {
        writeAndClose(100);
        assertEquals(1, readShortLE(22));
    }

    @Test
    void close_patchesSampleRate() throws IOException {
        writeAndClose(100);
        assertEquals(16_000, readIntLE(24));
    }

    @Test
    void close_patchesByteRate() throws IOException {
        // byteRate = sampleRate * channels * bitsPerSample/8 = 16000 * 1 * 2 = 32000
        writeAndClose(100);
        assertEquals(32_000, readIntLE(28));
    }

    @Test
    void close_patchesBlockAlign() throws IOException {
        // blockAlign = channels * bitsPerSample/8 = 1 * 2 = 2
        writeAndClose(100);
        assertEquals(2, readShortLE(32));
    }

    @Test
    void close_patchesBitsPerSample() throws IOException {
        writeAndClose(100);
        assertEquals(16, readShortLE(34));
    }

    @Test
    void close_patchesDataChunkSize() throws IOException {
        int dataBytes = 6400;
        writeAndClose(dataBytes);
        assertEquals(dataBytes, readIntLE(40));
    }

    @Test
    void close_withZeroData_producesValidMinimalHeader() throws IOException {
        writeAndClose(0);
        assertEquals("RIFF", headerChunkId(0));
        assertEquals(36, readIntLE(4));   // RIFF size = 36 when no data
        assertEquals(0,  readIntLE(40));  // data chunk size = 0
    }

    @Test
    void close_stereo48kHz_patchesCorrectByteRateAndBlockAlign() throws IOException {
        var stereo = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 48_000f, 16, 2, 4, 48_000f, false);
        try (var writer = new WavFileWriter(tempFile, stereo)) {
            writer.write(new byte[192]); // 24 stereo frames
        }
        assertEquals(2,       readShortLE(22));   // channels
        assertEquals(48_000,  readIntLE(24));      // sample rate
        assertEquals(192_000, readIntLE(28));      // byteRate = 48000 * 2ch * 2bytes
        assertEquals(4,       readShortLE(32));    // blockAlign = 2ch * 2bytes
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    void close_isIdempotent() throws IOException {
        var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO);
        writer.write(new byte[100]);
        writer.close();
        assertDoesNotThrow(writer::close);
    }

    @Test
    void tryWithResources_closesAndPatchesHeaderAutomatically() throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            writer.write(new byte[64]);
        }
        assertEquals("RIFF", headerChunkId(0));
        assertEquals(64, readIntLE(40));
    }

    // ── Format validation ─────────────────────────────────────────────────────

    @Test
    void constructor_rejectsBigEndian16bit() {
        var bigEndian = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, true);
        assertThrows(IllegalArgumentException.class,
                () -> new WavFileWriter(tempFile, bigEndian));
    }

    @Test
    void constructor_rejectsNonPcmEncoding() {
        var alaw = new AudioFormat(AudioFormat.Encoding.ALAW, 8_000f, 8, 1, 1, 8_000f, false);
        assertThrows(IllegalArgumentException.class,
                () -> new WavFileWriter(tempFile, alaw));
    }

    @Test
    void constructor_accepts8bitUnsignedBigEndian_notRejectedByEndianCheck() throws IOException {
        // The endian constraint only applies to multi-byte samples; 8-bit is fine either way
        var pcm8bit = new AudioFormat(
                AudioFormat.Encoding.PCM_UNSIGNED, 8_000f, 8, 1, 1, 8_000f, true);
        assertDoesNotThrow(() -> {
            try (var writer = new WavFileWriter(tempFile, pcm8bit)) {
                writer.write(new byte[8]);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeAndClose(int dataBytes) throws IOException {
        try (var writer = new WavFileWriter(tempFile, PCM_16KHZ_MONO)) {
            if (dataBytes > 0) writer.write(new byte[dataBytes]);
        }
    }

    private String headerChunkId(int offset) throws IOException {
        return new String(Files.readAllBytes(tempFile), offset, 4);
    }

    private int readIntLE(int offset) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(tempFile), offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private short readShortLE(int offset) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(tempFile), offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN).getShort();
    }
}
