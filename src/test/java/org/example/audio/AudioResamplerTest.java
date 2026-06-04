package org.example.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AudioResampler} — the package-private PCM converter.
 * Lives in the same package so it can instantiate the class directly.
 */
class AudioResamplerTest {

    // ── Format factories ──────────────────────────────────────────────────────

    private static AudioFormat mono(float rate) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 1, 2, rate, false);
    }

    private static AudioFormat stereo(float rate) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 2, 4, rate, false);
    }

    // ── PCM byte helpers ──────────────────────────────────────────────────────

    /** Packs int values into 16-bit little-endian PCM bytes (avoids casts at call sites). */
    private static byte[] asLE(int... values) {
        byte[] b = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            short v = (short) values[i];
            b[i * 2]     = (byte)  (v & 0xFF);
            b[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return b;
    }

    private static short[] fromLE(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
        }
        return s;
    }

    // ── Constructor validation ────────────────────────────────────────────────

    @Test
    void constructor_rejectsNon16BitSource() {
        var src8 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 8, 1, 1, 16_000f, false);
        assertThrows(IllegalArgumentException.class, () -> new AudioResampler(src8, mono(16_000)));
    }

    @Test
    void constructor_rejectsNon16BitTarget() {
        var tgt8 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 8, 1, 1, 16_000f, false);
        assertThrows(IllegalArgumentException.class, () -> new AudioResampler(mono(16_000), tgt8));
    }

    @Test
    void constructor_rejectsBigEndianSource() {
        var be = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, true);
        assertThrows(IllegalArgumentException.class, () -> new AudioResampler(be, mono(16_000)));
    }

    @Test
    void constructor_rejectsBigEndianTarget() {
        var be = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, true);
        assertThrows(IllegalArgumentException.class, () -> new AudioResampler(mono(16_000), be));
    }

    // ── Mono passthrough (ratio = 1.0) ────────────────────────────────────────

    @Test
    void convert_monoSameRate_returnsIdenticalSamples() {
        var r = new AudioResampler(mono(16_000), mono(16_000));
        byte[] input = asLE(100, 200, 300, 400);
        assertArrayEquals(new short[]{100, 200, 300, 400}, fromLE(r.convert(input)));
    }

    @Test
    void convert_monoSameRate_silenceStaysSilent() {
        var r = new AudioResampler(mono(16_000), mono(16_000));
        byte[] silence = new byte[200];
        assertArrayEquals(silence, r.convert(silence));
    }

    @Test
    void convert_emptyInput_returnsEmptyOutput() {
        var r = new AudioResampler(mono(16_000), mono(16_000));
        assertEquals(0, r.convert(new byte[0]).length);
    }

    // ── Stereo-to-mono mixing ─────────────────────────────────────────────────

    @Test
    void convert_stereoToMono_averagesLeftAndRight() {
        var r = new AudioResampler(stereo(16_000), mono(16_000));
        // Two stereo frames: (L=100, R=300) and (L=200, R=400)
        short[] result = fromLE(r.convert(asLE(100, 300, 200, 400)));
        assertEquals(2, result.length, "Two stereo frames → two mono frames");
        assertEquals(200, result[0], "avg(100, 300) = 200");
        assertEquals(300, result[1], "avg(200, 400) = 300");
    }

    @Test
    void convert_stereoToMono_symmetricChannels_producesNearZero() {
        var r = new AudioResampler(stereo(16_000), mono(16_000));
        short[] result = fromLE(r.convert(asLE(32767, -32768)));
        assertEquals(1, result.length);
        assertTrue(Math.abs(result[0]) <= 1,
                "avg(MAX_VALUE, MIN_VALUE) should be near zero, got " + result[0]);
    }

    @Test
    void convert_stereoSilence_producesMonoSilence() {
        var r = new AudioResampler(stereo(16_000), mono(16_000));
        byte[] stereoSilence = new byte[80]; // 20 stereo frames
        short[] result = fromLE(r.convert(stereoSilence));
        assertEquals(20, result.length);
        for (short s : result) assertEquals(0, s);
    }

    // ── Resampling (integer ratios for exact sample counts) ───────────────────

    @Test
    void convert_upsample2x_producesDoubleSamples() {
        // 8 kHz → 16 kHz: ratio = 0.5 → 2× more output samples
        var r = new AudioResampler(mono(8_000), mono(16_000));
        assertEquals(8, r.convert(asLE(0, 100, 200, 300)).length / 2,
                "4 samples × 2x upsample = 8 samples");
    }

    @Test
    void convert_downsample2x_producesHalfSamples() {
        // 32 kHz → 16 kHz: ratio = 2.0 → 2× fewer output samples
        var r = new AudioResampler(mono(32_000), mono(16_000));
        assertEquals(4, r.convert(asLE(0, 100, 200, 300, 400, 500, 600, 700)).length / 2,
                "8 samples × 0.5 downsample = 4 samples");
    }

    @Test
    void convert_upsample2x_linearlyInterpolatesMidpoints() {
        // Ramp 0 → 200 upsampled: expected [0, 100, 200, 200(hold)]
        var r = new AudioResampler(mono(8_000), mono(16_000));
        short[] result = fromLE(r.convert(asLE(0, 200)));
        assertEquals(4, result.length);
        assertEquals(0,   result[0]);
        assertEquals(100, result[1], "Midpoint of 0 and 200 via linear interpolation");
        assertEquals(200, result[2]);
        assertEquals(200, result[3], "Last sample is held when no next input available");
    }

    // ── Phase continuity across sequential chunks ─────────────────────────────

    @Test
    void convert_downsample2x_splitChunksSameTotalAsSingleChunk() {
        // ratio = 2.0 (32 kHz → 16 kHz): exact integer ratio keeps phase at 0
        var rA = new AudioResampler(mono(32_000), mono(16_000));
        var rB = new AudioResampler(mono(32_000), mono(16_000));

        byte[] full  = asLE(100, 200, 300, 400, 500, 600, 700, 800);
        byte[] half1 = asLE(100, 200, 300, 400);
        byte[] half2 = asLE(500, 600, 700, 800);

        int fullCount  = rA.convert(full).length / 2;
        int splitCount = rB.convert(half1).length / 2 + rB.convert(half2).length / 2;

        assertEquals(fullCount, splitCount,
                "Phase must carry across chunk boundaries to preserve total sample count");
    }

    @Test
    void convert_upsample2x_splitChunksSameTotalAsSingleChunk() {
        // ratio = 0.5 (8 kHz → 16 kHz)
        var rA = new AudioResampler(mono(8_000), mono(16_000));
        var rB = new AudioResampler(mono(8_000), mono(16_000));

        byte[] full  = asLE(0, 100, 200, 300, 400, 500, 600, 700);
        byte[] half1 = asLE(0, 100, 200, 300);
        byte[] half2 = asLE(400, 500, 600, 700);

        int fullCount  = rA.convert(full).length / 2;
        int splitCount = rB.convert(half1).length / 2 + rB.convert(half2).length / 2;

        assertEquals(fullCount, splitCount,
                "Upsampled split chunks must produce same total as single-chunk processing");
    }

    @Test
    void convert_multipleSequentialCalls_neverThrowArrayIndexOutOfBounds() {
        // Non-integer ratio exercises phase carryover logic across many chunks
        var r = new AudioResampler(mono(44_100), mono(16_000));
        int[] samples = new int[441]; // 10 ms at 44100 Hz
        for (int i = 0; i < samples.length; i++) samples[i] = i % 200;
        byte[] chunk = asLE(samples);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 20; i++) r.convert(chunk); // 200 ms of audio
        }, "Sequential 44100→16000 chunks must not throw array-index errors");
    }
}
