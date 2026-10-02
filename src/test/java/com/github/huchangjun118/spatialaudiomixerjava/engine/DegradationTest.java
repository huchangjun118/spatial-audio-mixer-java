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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies overload degradation: forced levels are deterministic and
 * self-consistent, and the auto state machine escalates under sustained load.
 */
class DegradationTest {

    private static final int SR = 44100;
    private static final int BLOCK = 256;

    private MixerConfig config(DegradationConfig deg) {
        return MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
                .doppler(DopplerConfig.defaultEnabled())
                .reverb(ReverbConfig.defaultEnabled())
                .degradation(deg)
                .build();
    }

    private BufferAudioSource sine() {
        float[] s = new float[SR];
        for (int i = 0; i < SR; i++) s[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);
        return new BufferAudioSource(s, true);
    }

    private SpatialAudioMixer mixer(DegradationConfig deg) {
        SpatialAudioMixer m = new SpatialAudioMixer(config(deg));
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).gain(0.3f).build());
        return m;
    }

    @Test
    void forcedMuted_outputIsSilence() {
        SpatialAudioMixer m = mixer(DegradationConfig.forced(DegradationLevel.MUTED));
        for (int b = 0; b < 3; b++) {
            float[][] out = m.renderBlock("l");
            assertEquals(DegradationLevel.MUTED, m.degradationLevel());
            for (int ch = 0; ch < 2; ch++) {
                for (int s = 0; s < BLOCK; s++) {
                    assertEquals(0f, out[ch][s], 0f, "muted output must be silence");
                }
            }
        }
    }

    @Test
    void forcedHalfRate_holdsEvenSamples() {
        SpatialAudioMixer m = mixer(DegradationConfig.forced(DegradationLevel.HALF_RATE));
        float[][] out = m.renderBlock("l");
        assertEquals(DegradationLevel.HALF_RATE, m.degradationLevel());
        // Odd frames must equal the preceding even frame (sample-and-hold).
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < BLOCK; i += 2) {
                assertEquals(out[ch][i], out[ch][i + 1], 0f,
                        "half-rate: odd sample " + (i + 1) + " must hold even sample " + i);
            }
        }
    }

    @Test
    void forcedHalfRate_isDeterministic() {
        SpatialAudioMixer a = mixer(DegradationConfig.forced(DegradationLevel.HALF_RATE));
        SpatialAudioMixer b = mixer(DegradationConfig.forced(DegradationLevel.HALF_RATE));
        for (int block = 0; block < 5; block++) {
            assertArrayEquals(a.renderBlock("l")[0], b.renderBlock("l")[0], 0f);
            assertArrayEquals(a.renderBlock("l")[1], b.renderBlock("l")[1], 0f);
        }
    }

    @Test
    void forcedReverbOff_matchesReverbDisabled() {
        SpatialAudioMixer degraded = mixer(DegradationConfig.forced(DegradationLevel.REVERB_OFF));
        // A mixer with reverb disabled in its config (the true baseline).
        MixerConfig cleanCfg = MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
                .doppler(DopplerConfig.defaultEnabled())
                .reverb(ReverbConfig.disabled())
                .degradation(DegradationConfig.disabled())
                .build();
        SpatialAudioMixer clean = new SpatialAudioMixer(cleanCfg);
        clean.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        clean.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).gain(0.3f).build());
        // Render several blocks to settle gains.
        for (int i = 0; i < 10; i++) {
            degraded.renderBlock("l");
            clean.renderBlock("l");
        }
        float[][] d = degraded.renderBlock("l");
        float[][] c = clean.renderBlock("l");
        // REVERB_OFF skips the wet path, so output matches a reverb-disabled mixer.
        assertArrayEquals(c[0], d[0], 1e-6f);
        assertArrayEquals(c[1], d[1], 1e-6f);
    }

    @Test
    void autoEscalation_underSustainedOverload() {
        // Tiny budget -> every block is over budget -> escalate.
        DegradationConfig deg = new DegradationConfig(
                true, 1e-9, 1.2, 0.7, 2, 10, null);
        SpatialAudioMixer m = mixer(deg);
        DegradationLevel first = m.degradationLevel();
        for (int i = 0; i < 30; i++) {
            m.renderBlock("l");
        }
        DegradationLevel last = m.degradationLevel();
        assertNotEquals(first, last, "level should escalate under sustained overload");
        // After enough blocks it should reach MUTED.
        assertEquals(DegradationLevel.MUTED, last, "should eventually mute");
    }

    @Test
    void autoEscalation_outputRemainsValid() {
        DegradationConfig deg = new DegradationConfig(
                true, 1e-9, 1.2, 0.7, 2, 10, null);
        SpatialAudioMixer m = mixer(deg);
        for (int i = 0; i < 30; i++) {
            float[][] out = m.renderBlock("l");
            for (int ch = 0; ch < 2; ch++) {
                for (int s = 0; s < BLOCK; s++) {
                    assertTrue(Float.isFinite(out[ch][s]), "output must stay finite during degradation");
                    assertTrue(out[ch][s] >= -1.0001f && out[ch][s] <= 1.0001f,
                            "output must be limited to [-1,1]");
                }
            }
        }
    }

    @Test
    void normalLoad_staysNormal() {
        // Generous budget -> no escalation.
        DegradationConfig deg = new DegradationConfig(
                true, 100.0, 1.2, 0.7, 2, 10, null);
        SpatialAudioMixer m = mixer(deg);
        for (int i = 0; i < 20; i++) {
            m.renderBlock("l");
        }
        assertEquals(DegradationLevel.NORMAL, m.degradationLevel(),
                "normal load should not trigger degradation");
    }
}
