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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that removed sources leave no reverb tail or crosstalk, and that
 * sources entering/leaving the audible range transition smoothly.
 */
class SourceRemovalTest {

    private static final int SR = 44100;
    private static final int BLOCK = 256;

    private MixerConfig config(boolean reverb) {
        return MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
                .doppler(DopplerConfig.defaultEnabled())
                .reverb(reverb ? ReverbConfig.defaultEnabled() : ReverbConfig.disabled())
                .degradation(DegradationConfig.disabled())
                .build();
    }

    private BufferAudioSource sine() {
        float[] s = new float[SR];
        for (int i = 0; i < SR; i++) s[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);
        return new BufferAudioSource(s, true);
    }

    private SpatialAudioMixer mixer(boolean reverb) {
        SpatialAudioMixer m = new SpatialAudioMixer(config(reverb));
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());
        return m;
    }

    private SpatialAudioMixer mixerNoDoppler(boolean reverb) {
        MixerConfig cfg = MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
                .doppler(DopplerConfig.disabled())
                .reverb(reverb ? ReverbConfig.defaultEnabled() : ReverbConfig.disabled())
                .degradation(DegradationConfig.disabled())
                .build();
        SpatialAudioMixer m = new SpatialAudioMixer(cfg);
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());
        return m;
    }

    @Test
    void removedSource_leavesNoTrace() {
        SpatialAudioMixer m = mixer(false);
        // Reference mixer that never had the source.
        SpatialAudioMixer ref = new SpatialAudioMixer(config(false));
        ref.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());

        m.removeSource("s");
        for (int b = 0; b < 5; b++) {
            float[][] out = m.renderBlock("l");
            float[][] expected = ref.renderBlock("l");
            assertArrayEquals(expected[0], out[0], 0f, "removed source must leave no trace");
            assertArrayEquals(expected[1], out[1], 0f);
        }
    }

    @Test
    void removedSource_leavesNoReverbTail() {
        SpatialAudioMixer m = mixer(true);
        // Let the reverb build up.
        for (int b = 0; b < 10; b++) m.renderBlock("l");
        m.removeSource("s");
        // After removal the per-source reverb state is discarded: no tail.
        for (int b = 0; b < 5; b++) {
            float[][] out = m.renderBlock("l");
            for (int ch = 0; ch < 2; ch++) {
                for (int s = 0; s < BLOCK; s++) {
                    assertEquals(0f, out[ch][s], 0f,
                            "removed source must leave no reverb tail");
                }
            }
        }
    }

    @Test
    void sourceLeavingAudibleRange_fadesOutSmoothly() {
        SpatialAudioMixer m = mixerNoDoppler(false);
        // Let gains settle at the initial position.
        for (int i = 0; i < 10; i++) m.renderBlock("l");
        // Move the source from close to far (beyond maxDistance).
        double prevRms = Double.MAX_VALUE;
        for (int d = 10; d <= 130; d += 5) {
            m.updateSource("s", SourceConfig.builder()
                    .position(new Vector3f(0, 0, d)).maxDistance(100).build());
            float[][] out = m.renderBlock("l");
            double rms = rms(out[0]);
            // RMS must decrease monotonically (smooth fade, no sudden jump).
            assertTrue(rms <= prevRms + 1e-9,
                    "rms must decrease as source leaves range: d=" + d + " rms=" + rms + " prev=" + prevRms);
            prevRms = rms;
        }
        // Far beyond maxDistance: render a few extra blocks to let the gain
        // smoother settle to zero, then the output must be silence.
        m.updateSource("s", SourceConfig.builder()
                .position(new Vector3f(0, 0, 200)).maxDistance(100).build());
        for (int i = 0; i < 10; i++) {
            float[][] out = m.renderBlock("l");
            prevRms = rms(out[0]);
        }
        assertEquals(0f, prevRms, 1e-6f, "source beyond maxDistance must be silent");
    }

    @Test
    void sourceReEnteringAudibleRange_fadesInSmoothly() {
        SpatialAudioMixer m = mixerNoDoppler(false);
        // Start beyond maxDistance.
        m.updateSource("s", SourceConfig.builder()
                .position(new Vector3f(0, 0, 150)).maxDistance(100).build());
        m.renderBlock("l"); // settle to silence
        // Move back into range.
        m.updateSource("s", SourceConfig.builder()
                .position(new Vector3f(0, 0, 10)).maxDistance(100).build());
        float[][] out = m.renderBlock("l");
        // The first block back must have finite, non-clicking samples.
        for (int ch = 0; ch < 2; ch++) {
            float prev = 0f;
            for (int s = 0; s < BLOCK; s++) {
                float cur = out[ch][s];
                assertTrue(Float.isFinite(cur), "output must be finite");
                float diff = Math.abs(cur - prev);
                assertTrue(diff < 0.5f, "fade-in must be smooth (no click) at sample " + s);
                prev = cur;
            }
        }
    }

    @Test
    void removedSource_doesNotCrosstalkIntoOtherSources() {
        SpatialAudioMixer m = mixer(false);
        m.addSource("s2", sine(),
                SourceConfig.builder().position(new Vector3f(10, 0, 10)).build());
        m.removeSource("s");
        // s2 must render exactly as if s never existed.
        SpatialAudioMixer ref = new SpatialAudioMixer(config(false));
        ref.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        ref.addSource("s2", sine(),
                SourceConfig.builder().position(new Vector3f(10, 0, 10)).build());
        for (int b = 0; b < 5; b++) {
            float[][] out = m.renderBlock("l");
            float[][] expected = ref.renderBlock("l");
            assertArrayEquals(expected[0], out[0], 0f);
            assertArrayEquals(expected[1], out[1], 0f);
        }
    }

    private double rms(float[] buf) {
        double sum = 0;
        for (float v : buf) sum += v * v;
        return Math.sqrt(sum / buf.length);
    }
}
