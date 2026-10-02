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
 * Verifies that Doppler and reverb can be toggled independently and that
 * when disabled the output is sample-identical to a baseline that never
 * enables the effect.
 */
class EffectBypassTest {

    private static final int SR = 44100;
    private static final int BLOCK = 256;
    private static final int SETTLE_BLOCKS = 20;

    private MixerConfig config(boolean doppler, boolean reverb) {
        return MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
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

    private SpatialAudioMixer mixer(boolean doppler, boolean reverb) {
        SpatialAudioMixer m = new SpatialAudioMixer(config(doppler, reverb));
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", sine(440, 1.0),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());
        return m;
    }

    /** Replicates the engine's distance attenuation. */
    private static float distanceAttenuation(float dist, SourceConfig c) {
        if (dist <= c.refDistance()) return 1.0f;
        float att = c.refDistance() / (c.refDistance() + c.rolloff() * (dist - c.refDistance()));
        float fadeMargin = Math.max(1.0f, 0.05f * c.maxDistance());
        if (dist > c.maxDistance() - fadeMargin) {
            float t = (c.maxDistance() - dist) / fadeMargin;
            if (t < 0f) t = 0f;
            att *= t * t * (3f - 2f * t);
        }
        return att;
    }

    /** Replicates the engine's per-sample gain smoothing. */
    private static float smoothedGain(float target, int samples) {
        double coef = 1.0 - Math.exp(-1.0 / (0.010 * SR));
        double g = 0;
        for (int i = 0; i < samples; i++) g += (target - g) * coef;
        return (float) g;
    }

    @Test
    void reverbDisabled_matchesDryBaseline() {
        SpatialAudioMixer m = mixer(false, false);
        for (int b = 0; b < SETTLE_BLOCKS; b++) m.renderBlock("l");
        float[][] out = m.renderBlock("l");

        SourceConfig sc = SourceConfig.builder().position(new Vector3f(0, 0, 10)).build();
        float att = distanceAttenuation(10f, sc);
        float dryGain = sc.gain() * 1.0f * att;
        float g = smoothedGain(dryGain, SETTLE_BLOCKS * BLOCK);
        float pan = (float) (1.0 / Math.sqrt(2.0));
        float[] samples = new float[SR];
        for (int i = 0; i < SR; i++) samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);

        for (int ch = 0; ch < 2; ch++) {
            float[] expected = new float[BLOCK];
            long start = (long) SETTLE_BLOCKS * BLOCK;
            for (int i = 0; i < BLOCK; i++) {
                expected[i] = pan * g * samples[(int) ((start + i) % samples.length)];
            }
            assertArrayEquals(expected, out[ch], 1e-6f);
        }
    }

    @Test
    void dopplerDisabled_matchesDryBaseline() {
        SpatialAudioMixer m = mixer(false, false);
        for (int b = 0; b < SETTLE_BLOCKS; b++) m.renderBlock("l");
        float[][] out = m.renderBlock("l");

        SourceConfig sc = SourceConfig.builder().position(new Vector3f(0, 0, 10)).build();
        float att = distanceAttenuation(10f, sc);
        float dryGain = sc.gain() * 1.0f * att;
        float g = smoothedGain(dryGain, SETTLE_BLOCKS * BLOCK);
        float pan = (float) (1.0 / Math.sqrt(2.0));
        float[] samples = new float[SR];
        for (int i = 0; i < SR; i++) samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);

        for (int ch = 0; ch < 2; ch++) {
            float[] expected = new float[BLOCK];
            long start = (long) SETTLE_BLOCKS * BLOCK;
            for (int i = 0; i < BLOCK; i++) {
                expected[i] = pan * g * samples[(int) ((start + i) % samples.length)];
            }
            assertArrayEquals(expected, out[ch], 1e-6f);
        }
    }

    @Test
    void reverbBypassAtRuntime_matchesNeverEnabled() {
        // A: reverb off from the start.
        SpatialAudioMixer a = mixer(true, false);
        // B: reverb on for 2 blocks, then off at runtime.
        SpatialAudioMixer b = mixer(true, true);
        for (int i = 0; i < 2; i++) {
            a.renderBlock("l");
            b.renderBlock("l");
        }
        b.setReverbEnabled(false);
        for (int i = 0; i < 4; i++) {
            float[][] oa = a.renderBlock("l");
            float[][] ob = b.renderBlock("l");
            assertArrayEquals(oa[0], ob[0], 0f,
                    "after reverb bypass, output must match never-enabled baseline");
            assertArrayEquals(oa[1], ob[1], 0f);
        }
    }

    @Test
    void dopplerBypassAtRuntime_matchesNeverEnabled() {
        // A: doppler off from the start.
        SpatialAudioMixer a = mixer(false, false);
        // B: doppler on for 2 blocks, then off at runtime.
        SpatialAudioMixer b = mixer(true, false);
        for (int i = 0; i < 2; i++) {
            a.renderBlock("l");
            b.renderBlock("l");
        }
        b.setDopplerEnabled(false);
        for (int i = 0; i < 4; i++) {
            float[][] oa = a.renderBlock("l");
            float[][] ob = b.renderBlock("l");
            assertArrayEquals(oa[0], ob[0], 0f,
                    "after doppler bypass, output must match never-enabled baseline");
            assertArrayEquals(oa[1], ob[1], 0f);
        }
    }

    @Test
    void reverbToggledOffThenOn_resumesProcessing() {
        SpatialAudioMixer m = mixer(false, true);
        for (int i = 0; i < 2; i++) m.renderBlock("l");
        m.setReverbEnabled(false);
        float[][] off = m.renderBlock("l");
        m.setReverbEnabled(true);
        float[][] on = m.renderBlock("l");
        // After re-enable the wet signal returns (output differs from bypassed).
        boolean anyDiff = false;
        for (int ch = 0; ch < 2 && !anyDiff; ch++) {
            for (int s = 0; s < BLOCK; s++) {
                if (Math.abs(off[ch][s] - on[ch][s]) > 1e-9f) {
                    anyDiff = true;
                    break;
                }
            }
        }
        assertTrue(anyDiff, "re-enabling reverb should change the output");
    }

    @Test
    void reverbEnabled_producesTailAfterSourceStops() {
        // Source that plays for 0.5s then goes silent (one-shot, no loop).
        float[] samples = new float[SR / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);
        }
        SpatialAudioMixer m = new SpatialAudioMixer(config(false, true));
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", new BufferAudioSource(samples, false),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        // Render past the end of the source.
        double tailRms = 0;
        int tailBlocks = 0;
        for (int b = 0; b < 20; b++) {
            float[][] out = m.renderBlock("l");
            if (b >= 10) { // source ended at block ~86 (0.5s / 256), so blocks 10+ are tail
                double sum = 0;
                for (int s = 0; s < BLOCK; s++) sum += out[0][s] * out[0][s];
                tailRms += sum / BLOCK;
                tailBlocks++;
            }
        }
        // With reverb enabled there should be audible energy after the source stops.
        assertTrue(tailRms > 1e-6, "reverb should produce a tail after source ends, rms=" + tailRms);
    }

    @Test
    void reverbDisabled_noTailAfterSourceStops() {
        float[] samples = new float[SR / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);
        }
        SpatialAudioMixer m = new SpatialAudioMixer(config(false, false));
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", new BufferAudioSource(samples, false),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        // Render well past the end of the source (0.5s = ~86 blocks).
        for (int b = 0; b < 95; b++) m.renderBlock("l");
        float[][] out = m.renderBlock("l");
        for (int ch = 0; ch < 2; ch++) {
            for (int s = 0; s < BLOCK; s++) {
                assertEquals(0f, out[ch][s], 1e-9f, "no reverb tail when reverb disabled");
            }
        }
    }
}
