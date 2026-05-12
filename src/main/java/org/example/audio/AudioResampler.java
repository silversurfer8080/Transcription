package org.example.audio;

import javax.sound.sampled.AudioFormat;

/**
 * Converts raw 16-bit LE PCM chunks between audio formats.
 *
 * <p>Two operations applied in sequence:
 * <ol>
 *   <li>Channel mixing — N-channel (stereo) to mono by averaging samples per frame.</li>
 *   <li>Sample-rate conversion — linear interpolation from source rate to target rate.</li>
 * </ol>
 *
 * <p>Only 16-bit signed little-endian PCM is supported — the project canonical format
 * and the default output of Windows audio drivers (Realtek, VB-Audio Virtual Cable).
 *
 * <p>This class is <em>stateful</em>: call {@link #convert(byte[])} in arrival order for
 * each sequential chunk. The fractional phase is carried across chunk boundaries so the
 * reconstructed stream is continuous and sample-accurate.
 */
final class AudioResampler {

    private final int srcChannels;
    private final double ratio;   // srcSampleRate / tgtSampleRate (e.g. 44100/16000 ≈ 2.756)
    private double phase = 0.0;   // fractional source position for the next output sample

    AudioResampler(AudioFormat src, AudioFormat tgt) {
        if (src.getSampleSizeInBits() != 16 || tgt.getSampleSizeInBits() != 16) {
            throw new IllegalArgumentException(
                    "Only 16-bit PCM supported; src=" + src.getSampleSizeInBits()
                    + " tgt=" + tgt.getSampleSizeInBits());
        }
        if (src.isBigEndian() || tgt.isBigEndian()) {
            throw new IllegalArgumentException("Only little-endian PCM supported");
        }
        this.srcChannels = src.getChannels();
        this.ratio = src.getSampleRate() / tgt.getSampleRate();
    }

    /**
     * Converts a chunk of source PCM bytes to the target format.
     * Must be called in chunk-arrival order — not thread-safe.
     *
     * @return converted PCM bytes; may be empty if the chunk was too small to produce output
     */
    byte[] convert(byte[] srcBytes) {
        short[] mono = deinterleaveToMono(srcBytes);
        return (ratio == 1.0) ? shortsToBytes(mono) : resampleMono(mono);
    }

    // ---- internals -------------------------------------------------------

    private short[] deinterleaveToMono(byte[] bytes) {
        int frameSizeBytes = srcChannels * 2;  // 16-bit = 2 bytes/sample
        int frameCount = bytes.length / frameSizeBytes;
        short[] mono = new short[frameCount];
        for (int i = 0; i < frameCount; i++) {
            long sum = 0;
            int base = i * frameSizeBytes;
            for (int ch = 0; ch < srcChannels; ch++) {
                int off = base + ch * 2;
                sum += (short) ((bytes[off] & 0xFF) | (bytes[off + 1] << 8));
            }
            mono[i] = (short) (sum / srcChannels);
        }
        return mono;
    }

    private byte[] resampleMono(short[] input) {
        // How many output samples fit: phase is the fractional source position for the
        // first output sample within this chunk; we advance by `ratio` per output sample.
        int outputCount = (int) Math.floor((input.length - phase) / ratio);
        if (outputCount <= 0) {
            phase -= input.length;
            return new byte[0];
        }

        short[] output = new short[outputCount];
        double pos = phase;
        for (int i = 0; i < outputCount; i++) {
            int idx = (int) pos;
            double frac = pos - idx;
            // Linear interpolation between input[idx] and input[idx+1].
            // At the last sample we hold the final value (negligible error for voice).
            short sa = idx < input.length ? input[idx] : input[input.length - 1];
            short sb = idx + 1 < input.length ? input[idx + 1] : sa;
            output[i] = (short) Math.round(sa + frac * (sb - sa));
            pos += ratio;
        }

        phase = pos - input.length;  // carry-over into next chunk; stays in [0, ratio)
        return shortsToBytes(output);
    }

    private static byte[] shortsToBytes(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[i * 2]     = (byte)  (s[i] & 0xFF);
            b[i * 2 + 1] = (byte) ((s[i] >> 8) & 0xFF);
        }
        return b;
    }
}
