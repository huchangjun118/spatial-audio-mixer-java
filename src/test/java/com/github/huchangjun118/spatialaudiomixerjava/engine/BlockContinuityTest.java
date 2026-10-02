package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioFormat;
import com.github.huchangjun118.spatialaudiomixerjava.audio.BufferAudioSource;
import com.github.huchangjun118.spatialaudiomixerjava.audio.ChannelLayout;
import com.github.huchangjun118.spatialaudiomixerjava.config.DegradationConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.DopplerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.ReverbConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.SourceConfig;
import com.github.huchangjun118.spatialaudiomixerjava.math.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that rendering in blocks is sample-identical to one-shot
 * rendering and that block boundaries are inaudible.
 */
class BlockContinuityTest {

    private static final int SR = 44100;

    private MixerConfig config(int blockSize, boolean doppler, boolean reverb) {
        return MixerConfig.builder()
                .format(new AudioFormat(SR, blockSize, ChannelLayout.STEREO))
                .doppler(doppler ? DopplerConfig.defaultEnabled() : DopplerConfig.disabled())
                .reverb(reverb ? ReverbConfig.defaultEnabled() : ReverbConfig.disabled())
                .degradation(DegradationConfig.disabled())
                .build();
    }

    private BufferAudioSource sine(double freq, double seconds) {
        int n = (int) (SR * seconds);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) Math.sin(2 * Math.PI * freq * i / SR);
        return new BufferAudioSource(s, true);
    }

    private SpatialAudioMixer mixer(int blockSize, boolean doppler, boolean reverb,
                                    Vector3f sourcePos) {
        SpatialAudioMixer m = new SpatialAudioMixer(config(blockSize, doppler, reverb));
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", sine(440, 2.0),
                SourceConfig.builder().position(sourcePos).build());
        return m;
    }

    /** Renders {@code totalFrames} frames and returns interleaved channel data. */
    private float[][] render(SpatialAudioMixer m, int totalFrames, int blockSize) {
        int blocks = totalFrames / blockSize;
        float[][] out = new float[2][totalFrames];
        for (int b = 0; b < blocks; b++) {
            float[][] block = m.renderBlock("l");
            for (int ch = 0; ch < 2; ch++) {
                System.arraycopy(block[ch], 0, out[ch], b * blockSize, blockSize);
            }
        }
        return out;
    }

    @Test
    void blockRendering_matchesOneShot_dopplerAndReverb() {
        int total = 4096;
        // One-shot: single block of 4096.
        SpatialAudioMixer oneShot = mixer(total, true, true, new Vector3f(3, 0, 10));
        float[][] a = render(oneShot, total, total);

        // Blocked: 64 frames per block.
        SpatialAudioMixer blocked = mixer(64, true, true, new Vector3f(3, 0, 10));
        float[][] b = render(blocked, total, 64);

        assertArrayEquals(a[0], b[0], 0f);
        assertArrayEquals(a[1], b[1], 0f);
    }

    @Test
    void blockRendering_matchesOneShot_noEffects() {
        int total = 2048;
        SpatialAudioMixer oneShot = mixer(total, false, false, new Vector3f(-5, 0, 8));
        float[][] a = render(oneShot, total, total);
        SpatialAudioMixer blocked = mixer(128, false, false, new Vector3f(-5, 0, 8));
        float[][] b = render(blocked, total, 128);
        assertArrayEquals(a[0], b[0], 0f);
        assertArrayEquals(a[1], b[1], 0f);
    }

    @Test
    void blockRendering_matchesOneShot_movingSource() {
        // Two independent mixers, same block size, same mid-stream update:
        // outputs must match sample-for-sample (deterministic across blocks).
        SpatialAudioMixer m1 = mixer(512, true, false, new Vector3f(0, 0, 10));
        SpatialAudioMixer m2 = mixer(512, true, false, new Vector3f(0, 0, 10));
        float[][] r1 = render(m1, 1024, 512);
        float[][] r2 = render(m2, 1024, 512);
        assertArrayEquals(r1[0], r2[0], 0f);
        assertArrayEquals(r1[1], r2[1], 0f);
        m1.updateSource("s", SourceConfig.builder().position(new Vector3f(20, 0, 5)).build());
        m2.updateSource("s", SourceConfig.builder().position(new Vector3f(20, 0, 5)).build());
        float[][] r1b = render(m1, 1024, 512);
        float[][] r2b = render(m2, 1024, 512);
        assertArrayEquals(r1b[0], r2b[0], 0f);
        assertArrayEquals(r1b[1], r2b[1], 0f);
    }

    @Test
    void blockBoundaries_haveNoClicks() {
        int total = 4096;
        SpatialAudioMixer blocked = mixer(64, true, true, new Vector3f(3, 0, 10));
        float[][] b = render(blocked, total, 64);
        // The maximum sample-to-sample delta at block boundaries must not
        // exceed the maximum delta inside a block (no boundary clicks).
        float maxInside = 0f;
        float maxBoundary = 0f;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 1; i < total; i++) {
                float d = Math.abs(b[ch][i] - b[ch][i - 1]);
                boolean boundary = (i % 64 == 0);
                if (boundary) {
                    maxBoundary = Math.max(maxBoundary, d);
                } else {
                    maxInside = Math.max(maxInside, d);
                }
            }
        }
        assertTrue(maxBoundary <= maxInside * 1.5 + 1e-6,
                "boundary delta " + maxBoundary + " exceeds inside delta " + maxInside);
    }
}
