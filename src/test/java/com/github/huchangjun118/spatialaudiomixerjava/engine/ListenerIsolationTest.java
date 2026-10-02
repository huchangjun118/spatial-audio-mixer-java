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
 * Verifies that a render failure is isolated to the failing listener: the
 * failing listener gets silence and a failure counter, while other listeners
 * are unaffected.
 */
class ListenerIsolationTest {

    private static final int SR = 44100;
    private static final int BLOCK = 256;

    private MixerConfig config() {
        return MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
                .doppler(DopplerConfig.defaultEnabled())
                .reverb(ReverbConfig.defaultEnabled())
                .degradation(DegradationConfig.disabled())
                .build();
    }

    private BufferAudioSource sine() {
        float[] s = new float[SR];
        for (int i = 0; i < SR; i++) s[i] = (float) Math.sin(2 * Math.PI * 440 * i / SR);
        return new BufferAudioSource(s, true);
    }

    @Test
    void invalidListener_getsSilenceAndFailureCount() {
        SpatialAudioMixer m = new SpatialAudioMixer(config());
        m.addListener("bad", new ListenerConfig(
                new Vector3f(Float.NaN, 0, 0), Vector3f.FORWARD, Vector3f.UP, 1f));
        m.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        float[][] out = m.renderBlock("bad");
        for (int ch = 0; ch < 2; ch++) {
            for (int s = 0; s < BLOCK; s++) {
                assertEquals(0f, out[ch][s], 0f, "failed listener must output silence");
            }
        }
        assertEquals(1, m.listenerFailureCount(), "failure must be counted");
    }

    @Test
    void failureOfOneListener_doesNotAffectOthers() {
        SpatialAudioMixer m = new SpatialAudioMixer(config());
        m.addListener("good", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        m.addListener("bad", new ListenerConfig(
                new Vector3f(Float.NaN, 0, 0), Vector3f.FORWARD, Vector3f.UP, 1f));
        m.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        // Render the bad listener first (it fails), then the good one.
        float[][] badOut = m.renderBlock("bad");
        float[][] goodOut = m.renderBlock("good");

        // The good listener must render normally.
        double goodRms = rms(goodOut[0]);
        assertTrue(goodRms > 0.01, "good listener must still render, rms=" + goodRms);
        // The bad listener is silence.
        for (int ch = 0; ch < 2; ch++) {
            for (int s = 0; s < BLOCK; s++) {
                assertEquals(0f, badOut[ch][s], 0f);
            }
        }
        assertEquals(1, m.listenerFailureCount());

        // Rendering the good listener again must be unaffected by the bad one.
        float[][] goodOut2 = m.renderBlock("good");
        // Compare against a reference mixer with only the good listener.
        SpatialAudioMixer ref = new SpatialAudioMixer(config());
        ref.addListener("good", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        ref.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());
        float[][] refOut = ref.renderBlock("good");
        float[][] refOut2 = ref.renderBlock("good");
        assertArrayEquals(refOut[0], goodOut[0], 0f);
        assertArrayEquals(refOut2[0], goodOut2[0], 0f);
    }

    @Test
    void unknownListener_getsSilenceAndFailureCount() {
        SpatialAudioMixer m = new SpatialAudioMixer(config());
        m.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        float[][] out = m.renderBlock("does-not-exist");
        for (int ch = 0; ch < 2; ch++) {
            for (int s = 0; s < BLOCK; s++) {
                assertEquals(0f, out[ch][s], 0f);
            }
        }
        assertEquals(1, m.listenerFailureCount());
    }

    private double rms(float[] buf) {
        double sum = 0;
        for (float v : buf) sum += v * v;
        return Math.sqrt(sum / buf.length);
    }
}
