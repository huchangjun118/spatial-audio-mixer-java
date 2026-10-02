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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialAudioMixerTest {

    private static final int SR = 44100;
    private static final int BLOCK = 256;

    private MixerConfig config(boolean doppler, boolean reverb) {
        return MixerConfig.builder()
                .format(new AudioFormat(SR, BLOCK, ChannelLayout.STEREO))
                .doppler(doppler ? DopplerConfig.defaultEnabled() : DopplerConfig.disabled())
                .reverb(reverb ? ReverbConfig.defaultEnabled() : ReverbConfig.disabled())
                .degradation(DegradationConfig.disabled())
                .build();
    }

    private BufferAudioSource sineSource(double freq, double seconds) {
        int n = (int) (SR * seconds);
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * freq * i / SR);
        }
        return new BufferAudioSource(samples, true);
    }

    private SpatialAudioMixer mixerWithSource(SourceConfig sc, boolean doppler, boolean reverb) {
        SpatialAudioMixer mixer = new SpatialAudioMixer(config(doppler, reverb));
        mixer.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO)
                .forward(Vector3f.FORWARD)
                .up(Vector3f.UP)
                .build());
        mixer.addSource("s", sineSource(440, 1.0), sc);
        return mixer;
    }

    private double rms(float[] buf) {
        double sum = 0;
        for (float v : buf) sum += v * v;
        return Math.sqrt(sum / buf.length);
    }

    @Test
    void sourceInFront_isCenterAndAudible() {
        SpatialAudioMixer mixer = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build(), true, true);
        float[][] out = mixer.renderBlock("l");
        assertEquals(2, out.length);
        double l = rms(out[0]);
        double r = rms(out[1]);
        assertTrue(l > 0.01, "left channel should be audible, rms=" + l);
        assertTrue(r > 0.01, "right channel should be audible, rms=" + r);
        assertEquals(l, r, l * 0.01 + 1e-6, "front source should be centered");
    }

    @Test
    void sourceToTheRight_isLouderRight() {
        SpatialAudioMixer mixer = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(8, 0, 4)).build(), true, false);
        float[][] out = mixer.renderBlock("l");
        double l = rms(out[0]);
        double r = rms(out[1]);
        assertTrue(r > l * 1.5, "right source should be louder on right: l=" + l + " r=" + r);
    }

    @Test
    void sourceToTheLeft_isLouderLeft() {
        SpatialAudioMixer mixer = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(-8, 0, 4)).build(), true, false);
        float[][] out = mixer.renderBlock("l");
        double l = rms(out[0]);
        double r = rms(out[1]);
        assertTrue(l > r * 1.5, "left source should be louder on left: l=" + l + " r=" + r);
    }

    @Test
    void distanceAttenuation_fartherIsQuieter() {
        SpatialAudioMixer near = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(0, 0, 5)).build(), true, false);
        SpatialAudioMixer far = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(0, 0, 50)).build(), true, false);
        double nearRms = rms(near.renderBlock("l")[0]);
        double farRms = rms(far.renderBlock("l")[0]);
        assertTrue(nearRms > farRms * 2, "near should be much louder: near=" + nearRms + " far=" + farRms);
    }

    @Test
    void sourceBeyondMaxDistance_isSilent() {
        SpatialAudioMixer mixer = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(0, 0, 150)).maxDistance(100).build(), true, false);
        float[][] out = mixer.renderBlock("l");
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < BLOCK; i++) {
                assertEquals(0f, out[ch][i], 1e-9, "source beyond maxDistance must be silent");
            }
        }
    }

    @Test
    void movement_updatesPanningWithoutClicks() {
        SpatialAudioMixer mixer = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build(), true, false);
        float[][] prev = mixer.renderBlock("l");
        for (int b = 0; b < 20; b++) {
            float x = -10 + b;
            mixer.updateSource("s", SourceConfig.builder().position(new Vector3f(x, 0, 10)).build());
            float[][] cur = mixer.renderBlock("l");
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < BLOCK; i++) {
                    assertTrue(Float.isFinite(cur[ch][i]), "output must be finite");
                    // No sample-sized jumps larger than a smoothed sine can produce.
                    float diff = Math.abs(cur[ch][i] - prev[ch][i]);
                    assertTrue(diff < 0.5f, "click detected at block " + b + " ch " + ch + " sample " + i + ": diff=" + diff);
                }
            }
            prev = cur;
        }
    }

    @Test
    void twoListeners_areIndependent() {
        SpatialAudioMixer mixer = new SpatialAudioMixer(config(true, false));
        mixer.addListener("l1", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        mixer.addListener("l2", ListenerConfig.builder()
                .position(new Vector3f(20, 0, 0)).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        mixer.addSource("s", sineSource(440, 1.0),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        float[][] o1 = mixer.renderBlock("l1");
        float[][] o2 = mixer.renderBlock("l2");
        // l2 is farther from the source -> quieter (different position).
        assertTrue(rms(o1[0]) > rms(o2[0]) * 1.2,
                "listeners must have independent outputs: l1=" + rms(o1[0]) + " l2=" + rms(o2[0]));
        // The two listeners must produce different (independent) outputs.
        boolean different = false;
        for (int i = 0; i < BLOCK; i++) {
            if (Math.abs(o1[0][i] - o2[0][i]) > 1e-6f) {
                different = true;
                break;
            }
        }
        assertTrue(different, "listener outputs must differ");
        // Rendering l1 again must be unaffected by the l2 render in between:
        // compare against a reference mixer that only has l1.
        SpatialAudioMixer ref = new SpatialAudioMixer(config(true, false));
        ref.addListener("l1", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        ref.addSource("s", sineSource(440, 1.0),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());
        float[][] r1 = ref.renderBlock("l1");
        assertArrayEquals(r1[0], o1[0], 0f);
        assertArrayEquals(r1[1], o1[1], 0f);
        float[][] o1Again = mixer.renderBlock("l1");
        float[][] r1Again = ref.renderBlock("l1");
        assertArrayEquals(r1Again[0], o1Again[0], 0f);
        assertArrayEquals(r1Again[1], o1Again[1], 0f);
    }

    @Test
    void sameInput_rendersIdenticallyEveryTime() {
        SpatialAudioMixer mixer = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(3, 0, 7)).build(), true, true);
        float[][] a = mixer.renderBlock("l");
        float[][] b = mixer.renderBlock("l");
        // Second block continues the stream, so compare a fresh mixer's first block twice.
        SpatialAudioMixer mixer2 = mixerWithSource(
                SourceConfig.builder().position(new Vector3f(3, 0, 7)).build(), true, true);
        float[][] c = mixer2.renderBlock("l");
        assertArrayEquals(a[0], c[0], 0f);
        assertArrayEquals(a[1], c[1], 0f);
        assertFalse(java.util.Arrays.equals(a[0], b[0]), "stream should advance between blocks");
    }
}
