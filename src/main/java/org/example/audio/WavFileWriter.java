package org.example.audio;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Streaming WAV writer for raw PCM data.
 *
 * <p><b>Why not {@code AudioSystem.write(...)}?</b> That helper requires the
 * full audio stream up front (it needs to know the data length to write the
 * RIFF header). For an interview that may run an hour we want to flush each
 * captured chunk to disk as it arrives, so we write a 44-byte placeholder
 * header on construction, append PCM bytes via {@link #write(byte[])}, and
 * patch the two size fields back in {@link #close()} using
 * {@link RandomAccessFile#seek(long)}.
 *
 * <p><b>Format constraints.</b> WAV's PCM container is little-endian. We
 * accept the {@link AudioFormat} unchanged but reject anything that won't
 * round-trip cleanly: encoding must be PCM_SIGNED (or PCM_UNSIGNED for 8-bit),
 * and multi-byte samples must be little-endian. The capture pipeline already
 * picks 16 kHz / 16-bit / mono / signed / little-endian as the project
 * default, so this is essentially a guard against future misuse.
 */
public class WavFileWriter implements AutoCloseable {

    private static final int HEADER_SIZE = 44;

    private final RandomAccessFile raf;
    private final AudioFormat format;
    private final Path path;
    private long dataBytesWritten = 0;
    private boolean closed = false;

    public WavFileWriter(Path path, AudioFormat format) throws IOException {
        this.path = path;
        this.format = format;
        validateFormat(format);
        this.raf = new RandomAccessFile(path.toFile(), "rw");
        this.raf.setLength(0);
        writePlaceholderHeader();
    }

    /** Appends a chunk of PCM bytes. Thread-safe. */
    public synchronized void write(byte[] pcm) throws IOException {
        write(pcm, 0, pcm.length);
    }

    public synchronized void write(byte[] pcm, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("WavFileWriter is closed");
        }
        raf.write(pcm, off, len);
        dataBytesWritten += len;
    }

    /** Patches header sizes and closes the file. Idempotent. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            patchHeader();
        } finally {
            raf.close();
        }
    }

    public Path path() {
        return path;
    }

    public long dataBytesWritten() {
        return dataBytesWritten;
    }

    private void writePlaceholderHeader() throws IOException {
        raf.write(new byte[HEADER_SIZE]);
    }

    // Maximum safe data size: RIFF uses unsigned 32-bit lengths, but Java's int is
    // signed — so the safe ceiling is Integer.MAX_VALUE (~2.1 GB). At 32 KB/s
    // (16 kHz/16-bit/mono) that is ~18 hours of audio; well beyond any interview.
    private static final long MAX_WAV_DATA_BYTES = Integer.MAX_VALUE - HEADER_SIZE;

    private void patchHeader() throws IOException {
        if (dataBytesWritten > MAX_WAV_DATA_BYTES) {
            throw new IOException(
                    "WAV data exceeds 2 GB limit (" + dataBytesWritten + " bytes written). "
                    + "The file is complete but its header cannot be patched correctly.");
        }
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        int bitsPerSample = format.getSampleSizeInBits();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        long fileSizeMinus8 = (HEADER_SIZE - 8L) + dataBytesWritten;

        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt((int) fileSizeMinus8);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);                    // fmt chunk size for PCM
        bb.putShort((short) 1);           // audio format = PCM
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(byteRate);
        bb.putShort((short) blockAlign);
        bb.putShort((short) bitsPerSample);
        bb.put("data".getBytes());
        bb.putInt((int) dataBytesWritten);

        raf.seek(0);
        raf.write(bb.array());
    }

    private static void validateFormat(AudioFormat fmt) {
        AudioFormat.Encoding enc = fmt.getEncoding();
        boolean isPcm = AudioFormat.Encoding.PCM_SIGNED.equals(enc)
                || AudioFormat.Encoding.PCM_UNSIGNED.equals(enc);
        if (!isPcm) {
            throw new IllegalArgumentException("WAV writer requires PCM encoding, got " + enc);
        }
        if (fmt.getSampleSizeInBits() > 8 && fmt.isBigEndian()) {
            throw new IllegalArgumentException(
                    "WAV PCM samples must be little-endian; got big-endian " + fmt);
        }
    }
}
