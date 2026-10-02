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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies copy-on-write snapshot consistency: concurrent updates and
 * renders never expose half-updated state, and every rendered block
 * corresponds to one self-consistent snapshot.
 */
class ConcurrentUpdateTest {

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
    void snapshotIsImmutable_afterUpdate() {
        SpatialAudioMixer mixer = new SpatialAudioMixer(config());
        mixer.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        mixer.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        Snapshot before = mixer.snapshot();
        assertEquals(1, before.sources().size());

        mixer.updateSource("s", SourceConfig.builder().position(new Vector3f(5, 0, 5)).build());
        Snapshot after = mixer.snapshot();

        assertNotSame(before, after);
        // The old snapshot is unchanged (immutable).
        assertEquals(1, before.sources().size());
        assertEquals(new Vector3f(0, 0, 10), before.sources().get("s").config().position());
        // The new snapshot has the update.
        assertEquals(new Vector3f(5, 0, 5), after.sources().get("s").config().position());
    }

    @Test
    void renderUsesSingleSnapshot_midBlockUpdateDoesNotAffectBlock() {
        SpatialAudioMixer mixer = new SpatialAudioMixer(config());
        mixer.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        mixer.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        // Reference mixer that is never updated.
        SpatialAudioMixer ref = new SpatialAudioMixer(config());
        ref.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        ref.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        // Render one block, then update, then render another. The first block
        // must not contain any trace of the update (it used the old snapshot).
        float[][] first = mixer.renderBlock("l");
        float[][] refFirst = ref.renderBlock("l");
        assertArrayEquals(refFirst[0], first[0], 0f,
                "first block must match a mixer that never gets updated");
        assertArrayEquals(refFirst[1], first[1], 0f);

        mixer.updateSource("s", SourceConfig.builder().position(new Vector3f(100, 0, 10)).build());
        float[][] second = mixer.renderBlock("l");
        float[][] refSecond = ref.renderBlock("l");
        // The second block must differ from the never-updated reference.
        boolean differs = false;
        for (int ch = 0; ch < 2 && !differs; ch++) {
            for (int i = 0; i < BLOCK; i++) {
                if (Math.abs(second[ch][i] - refSecond[ch][i]) > 1e-9f) {
                    differs = true;
                    break;
                }
            }
        }
        assertTrue(differs, "second block should reflect the update");
    }

    @Test
    void concurrentUpdatesAndRenders_neverFailOrProduceInvalidSamples() throws InterruptedException {
        SpatialAudioMixer mixer = new SpatialAudioMixer(config());
        mixer.addListener("l", ListenerConfig.builder()
                .position(Vector3f.ZERO).forward(Vector3f.FORWARD).up(Vector3f.UP).build());
        mixer.addSource("s", sine(),
                SourceConfig.builder().position(new Vector3f(0, 0, 10)).build());

        int iterations = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        // Writer 1: position updates.
        executor.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    float x = (i % 2 == 0) ? 0f : 50f;
                    mixer.updateSource("s",
                            SourceConfig.builder().position(new Vector3f(x, 0, 10)).build());
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });
        // Writer 2: gain updates.
        executor.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    float g = (i % 2 == 0) ? 1f : 0.5f;
                    mixer.updateSource("s",
                            SourceConfig.builder().position(new Vector3f(0, 0, 10)).gain(g).build());
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });
        // Reader: renders blocks.
        executor.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    float[][] out = mixer.renderBlock("l");
                    for (int ch = 0; ch < 2; ch++) {
                        for (int s = 0; s < BLOCK; s++) {
                            if (!Float.isFinite(out[ch][s])) {
                                failures.incrementAndGet();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS), "timed out");
        assertEquals(0, failures.get(), "no failures or invalid samples allowed");
    }

    private double rms(float[] buf) {
        double sum = 0;
        for (float v : buf) sum += v * v;
        return Math.sqrt(sum / buf.length);
    }
}
