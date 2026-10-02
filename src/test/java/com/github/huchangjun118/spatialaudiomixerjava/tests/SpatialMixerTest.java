package com.github.huchangjun118.spatialaudiomixerjava.tests;

import com.github.huchangjun118.spatialaudiomixerjava.engine.SpatialMixer;
import com.github.huchangjun118.spatialaudiomixerjava.model.ChannelLayout;
import com.github.huchangjun118.spatialaudiomixerjava.model.DegradationMode;
import com.github.huchangjun118.spatialaudiomixerjava.model.DegradationPolicy;
import com.github.huchangjun118.spatialaudiomixerjava.model.DopplerParams;
import com.github.huchangjun118.spatialaudiomixerjava.model.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.ReverbParams;
import com.github.huchangjun118.spatialaudiomixerjava.model.RenderResult;
import com.github.huchangjun118.spatialaudiomixerjava.model.SourceConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialMixerTest {

    private static MixerConfig cfg(DegradationPolicy policy, boolean doppler, boolean reverb,
                                   int blockSize, ChannelLayout layout) {
        return new MixerConfig(48000, blockSize, layout, 100.0, 1.0, 1.0, 64.0,
                policy,
                new DopplerParams(doppler, 343.0, 0.1),
                new ReverbParams(reverb, 0.85, 0.3, 0.25));
    }

    private static DegradationPolicy unlimited() {
        return DegradationPolicy.unlimited(DegradationMode.LIMIT_SOURCES);
    }

    private static float[] noise(int n, int seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) (r.nextDouble() * 0.4 - 0.2);
        }
        return x;
    }

    private static float[] concat(List<float[]> blocks) {
        int total = blocks.stream().mapToInt(a -> a.length).sum();
        float[] out = new float[total];
        int p = 0;
        for (float[] b : blocks) {
            System.arraycopy(b, 0, out, p, b.length);
            p += b.length;
        }
        return out;
    }

    private static float[] channel(RenderResult r, int ch) {
        return r.output()[ch];
    }

    // 1. Chunked vs one-shot rendering must be sample identical (no effects).
    @Test
    void chunkedRenderingMatchesOneShotStereo() {
        int total = 4000;
        float[] input = noise(total, 42);

        SpatialMixer oneShot = new SpatialMixer(cfg(unlimited(), false, false, 257,
                ChannelLayout.STEREO));
        oneShot.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        oneShot.addSource(new SourceConfig("S", new Vec3(5, 0, 10), Vec3.ZERO, 0.8,
                0.0, 1.0));
        oneShot.enqueueInput("S", input, 0, total);
        RenderResult whole = oneShot.renderBlock("L", total);

        SpatialMixer chunked = new SpatialMixer(cfg(unlimited(), false, false, 257,
                ChannelLayout.STEREO));
        chunked.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        chunked.addSource(new SourceConfig("S", new Vec3(5, 0, 10), Vec3.ZERO, 0.8,
                0.0, 1.0));
        chunked.enqueueInput("S", input, 0, total);
        List<float[]> lChunks = new ArrayList<>();
        List<float[]> rChunks = new ArrayList<>();
        int[] sizes = {1, 333, 256, 7, 1024, 999, 1380};
        int pos = 0;
        for (int sz : sizes) {
            RenderResult r = chunked.renderBlock("L", sz);
            assertEquals(sz, r.samples());
            lChunks.add(channel(r, 0));
            rChunks.add(channel(r, 1));
            pos += sz;
        }
        assertEquals(total, pos);
        assertArrayEquals(channel(whole, 0), concat(lChunks), 0f);
        assertArrayEquals(channel(whole, 1), concat(rChunks), 0f);
        assertFalse(whole.degraded());
    }

    // 1b. No effects, source within reference distance in front of the listener:
    //     mono output is bit-identical to the input times the unity gains.
    @Test
    void baselinePassThroughIsExact() {
        int n = 1500;
        float[] input = noise(n, 99);
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 300,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 0.5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", input, 0, n);
        float[] out = m.renderBlock("L", n).output()[0];
        assertArrayEquals(input, out, 0f);
    }

    // 2. Moving a source changes the pan field, and a far source attenuates toward zero.
    @Test
    void movingSourceUpdatesPanAndDistance() {
        int n = 2048;
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 512,
                ChannelLayout.STEREO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(-10, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(n, 1), 0, n);
        RenderResult leftBlock = m.renderBlock("L", 512);
        double leftEnergy = energy(leftBlock.output()[0]);
        double rightEnergy = energy(leftBlock.output()[1]);
        assertTrue(leftEnergy > rightEnergy, "source left should dominate L channel");

        m.updateSource(new SourceConfig("S", new Vec3(10, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        RenderResult rightBlock = m.renderBlock("L", 512);
        double leftEnergy2 = energy(rightBlock.output()[0]);
        double rightEnergy2 = energy(rightBlock.output()[1]);
        assertTrue(rightEnergy2 > leftEnergy2, "after move, R channel dominates");

        // Move beyond audible range -> output settles to zero smoothly.
        m.updateSource(new SourceConfig("S", new Vec3(0, 0, 200), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.renderBlock("L", 4096);
        RenderResult far = m.renderBlock("L", 512);
        assertEquals(0.0, energy(far.output()[0]), 1e-12);
        assertEquals(0.0, energy(far.output()[1]), 1e-12);
    }

    // 3. Two listeners get independent outputs.
    @Test
    void multipleListenersAreIndependent() {
        int n = 1024;
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 512,
                ChannelLayout.STEREO));
        m.addListener(new ListenerConfig("L1", new Vec3(-10, 0, 0), Vec3.ZERO, 1.0));
        m.addListener(new ListenerConfig("L2", new Vec3(10, 0, 0), Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", Vec3.ZERO, Vec3.ZERO, 1.0, 0.0, 1.0));
        m.enqueueInput("S", noise(n, 7), 0, n);
        List<RenderResult> all = m.renderAll(n);
        assertEquals(2, all.size());
        // Opposite sides relative to the source should pan oppositely.
        float[] l1 = all.get(0).output()[0];
        float[] l2 = all.get(1).output()[0];
        assertTrue(energy(l1) != energy(l2) || l1[0] != l2[0]
                || energy(all.get(0).output()[1]) != energy(all.get(1).output()[1]));
    }

    // 4. Effects toggled off mid stream are bit-identical to never-enabled baseline.
    @Test
    void disabledEffectsMatchBaselineExactly() {
        int n = 3000;
        float[] input = noise(n, 9);
        Vec3 pos = new Vec3(3, 0, 8);

        RenderResult baseline = renderOnce(cfg(unlimited(), false, false, 512,
                ChannelLayout.STEREO), input, pos, false);
        RenderResult withReverbOff = renderOnce(cfg(unlimited(), false, false, 512,
                ChannelLayout.STEREO), input, pos, true);
        assertArrayEquals(baseline.output()[0], withReverbOff.output()[0], 0f);
        assertArrayEquals(baseline.output()[1], withReverbOff.output()[1], 0f);
    }

    // 5. Doppler on changes output, off does not; both modes deterministic across chunking.
    @Test
    void dopplerDeterminismAndBypass() {
        int n = 4096;
        float[] input = noise(n, 13);
        Vec3 vel = new Vec3(0, 0, 30);
        Vec3 pos = new Vec3(0, 0, 20);

        SpatialMixer base = new SpatialMixer(cfg(unlimited(), false, false, 512,
                ChannelLayout.MONO));
        base.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        base.addSource(new SourceConfig("S", pos, vel, 1.0, 0.0, 1.0));
        base.enqueueInput("S", input, 0, n);
        RenderResult b = base.renderBlock("L", n);

        SpatialMixer dop = new SpatialMixer(cfg(unlimited(), true, false, 512,
                ChannelLayout.MONO));
        dop.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        dop.addSource(new SourceConfig("S", pos, vel, 1.0, 0.0, 1.0));
        dop.enqueueInput("S", input, 0, n);
        RenderResult d1 = dop.renderBlock("L", n);
        assertDifferent(b.output()[0], d1.output()[0]);

        SpatialMixer dopChunk = new SpatialMixer(cfg(unlimited(), true, false, 512,
                ChannelLayout.MONO));
        dopChunk.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        dopChunk.addSource(new SourceConfig("S", pos, vel, 1.0, 0.0, 1.0));
        dopChunk.enqueueInput("S", input, 0, n);
        List<float[]> chunks = new ArrayList<>();
        for (int sz : new int[]{1000, 777, 2319}) {
            chunks.add(dopChunk.renderBlock("L", sz).output()[0]);
        }
        assertArrayEquals(d1.output()[0], concat(chunks), 0f);
    }

    // 5b. Reverb on changes the output; chunked and one-shot stay identical.
    @Test
    void reverbChangesOutputButStaysChunkDeterministic() {
        int n = 4096;
        float[] input = noise(n, 31);
        Vec3 pos = new Vec3(2, 0, 6);

        SpatialMixer base = new SpatialMixer(cfg(unlimited(), false, false, 333,
                ChannelLayout.MONO));
        base.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        base.addSource(new SourceConfig("S", pos, Vec3.ZERO, 1.0, 0.9, 1.0));
        base.enqueueInput("S", input, 0, n);
        float[] baseline = base.renderBlock("L", n).output()[0];

        SpatialMixer rev = new SpatialMixer(cfg(unlimited(), false, true, 333,
                ChannelLayout.MONO));
        rev.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        rev.addSource(new SourceConfig("S", pos, Vec3.ZERO, 1.0, 0.9, 1.0));
        rev.enqueueInput("S", input, 0, n);
        float[] wet = rev.renderBlock("L", n).output()[0];
        assertDifferent(baseline, wet);

        SpatialMixer revChunk = new SpatialMixer(cfg(unlimited(), false, true, 333,
                ChannelLayout.MONO));
        revChunk.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        revChunk.addSource(new SourceConfig("S", pos, Vec3.ZERO, 1.0, 0.9, 1.0));
        revChunk.enqueueInput("S", input, 0, n);
        List<float[]> chunks = new ArrayList<>();
        for (int sz : new int[]{500, 1333, 2263}) {
            chunks.add(revChunk.renderBlock("L", sz).output()[0]);
        }
        assertArrayEquals(wet, concat(chunks), 0f);
    }

    // 5c. A source re-added under the same id gets an independent buffer/state.
    @Test
    void reAddedSourceRendersFresh() {
        int n = 1024;
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 256,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(n, 1), 0, n);
        m.renderBlock("L", n);
        m.removeSource("S");
        m.renderBlock("L", SpatialMixer.DEATH_FADE_SAMPLES);
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(n, 2), 0, n);
        RenderResult r = m.renderBlock("L", n);
        assertTrue(energy(r.output()[0]) > 0.0);
        assertEquals(0, m.getDroppedSamples("S"));
    }

    // 6. Removed source leaves no residual energy once its fade completes.
    @Test
    void removedSourceLeavesNoResidual() {
        int n = 8192;
        float[] input = noise(n, 21);
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, true, 512,
                ChannelLayout.STEREO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(2, 0, 6), Vec3.ZERO, 1.0,
                0.9, 1.0));
        m.enqueueInput("S", input, 0, n);
        m.renderBlock("L", 1024);
        assertTrue(energy(m.renderBlock("L", 512).output()[0]) > 0.0);
        m.removeSource("S");
        // First blocks may still contain the fade, but after fade length -> silence.
        m.renderBlock("L", 1024);
        RenderResult tail = m.renderBlock("L", 4096);
        assertEquals(0.0, energy(tail.output()[0]), 0.0);
        assertEquals(0.0, energy(tail.output()[1]), 0.0);
    }

    // 7. Overload: LIMIT_SOURCES flags degradation and silences the far source.
    @Test
    void overloadLimitSourcesIsDeterministic() {
        int n = 2048;
        DegradationPolicy policy = new DegradationPolicy(DegradationMode.LIMIT_SOURCES,
                2, 1, 1, 1); // 1 audible source capacity
        SpatialMixer m = new SpatialMixer(cfg(policy, false, false, 512,
                ChannelLayout.STEREO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("near", new Vec3(0, 0, 2), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.addSource(new SourceConfig("far", new Vec3(0, 3, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("near", noise(n, 1), 0, n);
        m.enqueueInput("far", noise(n, 2), 0, n);
        RenderResult r1 = m.renderBlock("L", n);
        assertTrue(r1.degraded());
        assertEquals(DegradationMode.LIMIT_SOURCES, r1.degradationMode());
        // After ramp-down, the far source contributes nothing; only near remains.
        RenderResult r2 = m.renderBlock("L", n);
        // Compare against a mixer with only the near source.
        SpatialMixer ref = new SpatialMixer(cfg(unlimited(), false, false, 512,
                ChannelLayout.STEREO));
        ref.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        ref.addSource(new SourceConfig("near", new Vec3(0, 0, 2), Vec3.ZERO, 1.0,
                0.0, 1.0));
        ref.enqueueInput("near", noise(n, 1), 0, n);
        ref.renderBlock("L", n); // align clock/grid
        float[] refSecond = ref.renderBlock("L", n).output()[0];
        assertArrayEquals(refSecond, r2.output()[0], 1e-6f);
    }

    // 8. Overload: DOWNSAMPLE flags and stays deterministic across chunking.
    @Test
    void overloadDownsampleChunkedMatchesOneShot() {
        int total = 3000;
        float[] input = noise(total, 3);
        DegradationPolicy policy = new DegradationPolicy(DegradationMode.DOWNSAMPLE,
                4, 100, 1, 0);
        MixerConfig c = cfg(policy, false, false, 512, ChannelLayout.MONO);

        SpatialMixer a = new SpatialMixer(c);
        a.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        a.addSource(new SourceConfig("S", new Vec3(0, 0, 3), Vec3.ZERO, 1.0,
                0.0, 1.0));
        a.enqueueInput("S", input, 0, total);
        RenderResult whole = a.renderBlock("L", total);
        assertTrue(whole.degraded());

        SpatialMixer b = new SpatialMixer(c);
        b.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        b.addSource(new SourceConfig("S", new Vec3(0, 0, 3), Vec3.ZERO, 1.0,
                0.0, 1.0));
        b.enqueueInput("S", input, 0, total);
        List<float[]> chunks = new ArrayList<>();
        for (int sz : new int[]{333, 1111, 1556}) {
            chunks.add(b.renderBlock("L", sz).output()[0]);
        }
        assertArrayEquals(whole.output()[0], concat(chunks), 0f);
    }

    // 9. Overload: MUTE degrades to (ramped) silence and never throws.
    @Test
    void overloadMuteProducesSilenceEventually() {
        int n = 2048;
        DegradationPolicy policy = new DegradationPolicy(DegradationMode.MUTE,
                2, 1, 1, 1);
        SpatialMixer m = new SpatialMixer(cfg(policy, false, false, 256,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 3), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.addSource(new SourceConfig("S2", new Vec3(2, 0, 3), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(n * 4, 5), 0, n * 4);
        m.enqueueInput("S2", noise(n * 4, 6), 0, n * 4);
        m.renderBlock("L", n);
        RenderResult later = m.renderBlock("L", n);
        assertTrue(later.degraded());
        assertEquals(0.0, energy(later.output()[0]), 1e-9);
    }

    // 10. Concurrent parameter updates never break rendering; output always finite.
    @Test
    void concurrentUpdatesProduceConsistentSnapshots() throws Exception {
        int n = 4096;
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), true, true, 256,
                ChannelLayout.STEREO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        for (int i = 0; i < 6; i++) {
            String id = "S" + i;
            m.addSource(new SourceConfig(id, new Vec3(i, 0, 10), Vec3.ZERO, 0.5,
                    0.3, 1.0));
            m.enqueueInput(id, noise(n * 2, i), 0, n * 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int t = 0; t < 3; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        String id = "S" + (i % 6);
                        m.updateSource(new SourceConfig(id,
                                new Vec3((i + seed) % 20 - 10, 0, (i * 3) % 30),
                                new Vec3(seed - 1, 0, 1), 0.5, 0.3, 1.0));
                    }
                } catch (Throwable e) {
                    error.set(e);
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 40; i++) {
                    RenderResult r = m.renderBlock("L", 256);
                    for (float[] ch : r.output()) {
                        for (float v : ch) {
                            assertTrue(Float.isFinite(v));
                        }
                    }
                }
            } catch (Throwable e) {
                error.set(e);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(error.get());
    }

    // 11. Block boundaries do not produce clicks: large sample-to-sample jumps absent
    //     at internal grid edges for a source that starts/stops contribution smoothly.
    @Test
    void noClicksAtBlockBoundaries() {
        int n = 4096;
        float[] input = noise(n, 11);
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 128,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", input, 0, n);
        float[] out = m.renderBlock("L", n).output()[0];
        float maxStep = 0;
        for (int i = 1; i < n; i++) {
            // Boundary samples at 128 multiples.
            if (i % 128 == 0) {
                maxStep = Math.max(maxStep, Math.abs(out[i] - out[i - 1]));
            }
        }
        // Input itself is bounded to 0.2 amplitude; a click would be a near-full jump.
        assertTrue(maxStep < 0.5f, "boundary step was " + maxStep);
    }

    // 12. Re-entering audible range resumes sound without a discontinuity explosion.
    @Test
    void sourceReentersAudibleRangeSmoothly() {
        int n = 2048;
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 256,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(n * 6, 17), 0, n * 6);
        m.renderBlock("L", n);
        m.updateSource(new SourceConfig("S", new Vec3(0, 0, 150), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.renderBlock("L", n * 2);
        m.updateSource(new SourceConfig("S", new Vec3(0, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        float[] back = m.renderBlock("L", n).output()[0];
        float maxStep = 0;
        for (int i = 1; i < back.length; i++) {
            maxStep = Math.max(maxStep, Math.abs(back[i] - back[i - 1]));
        }
        assertTrue(maxStep < 0.5f);
        assertTrue(energy(back) > 0.0);
    }

    // 13. Rendering an unknown listener throws (fatal API misuse) but renderAll
    //     results stay isolated; listener removal stops its state entirely.
    @Test
    void listenerRemovalStopsRendering() {
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 256,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 5), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(512, 1), 0, 512);
        assertNotNull(m.renderBlock("L", 256));
        m.removeListener("L");
        assertTrue(m.renderAll(256).isEmpty());
    }

    // 14. A failing listener result is isolated: it returns silence + error while
    //     other listeners keep rendering normally.
    @Test
    void listenerFailureIsIsolated() {
        int n = 512;
        SpatialMixer m = new SpatialMixer(cfg(unlimited(), false, false, 256,
                ChannelLayout.MONO));
        m.addListener(new ListenerConfig("good", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addListener(new ListenerConfig("bad", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", new Vec3(0, 0, 3), Vec3.ZERO, 1.0,
                0.0, 1.0));
        m.enqueueInput("S", noise(n, 1), 0, n);
        m.setFaultInjected("bad", true);
        List<RenderResult> all = m.renderAll(n);
        assertEquals(2, all.size());
        RenderResult bad = all.get(0);
        RenderResult good = all.get(1);
        assertEquals("bad", bad.listenerId());
        assertTrue(bad.error() != null && bad.error().contains("injected"));
        assertEquals(0.0, energy(bad.output()[0]), 0.0);
        // The healthy listener is completely unaffected.
        assertTrue(energy(good.output()[0]) > 0.0);
    }

    // ----------------------------------------------------------------- helpers

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }

    private static RenderResult renderOnce(MixerConfig c, float[] input, Vec3 pos,
                                           boolean unusedReverbFlag) {
        SpatialMixer m = new SpatialMixer(c);
        m.addListener(new ListenerConfig("L", Vec3.ZERO, Vec3.ZERO, 1.0));
        m.addSource(new SourceConfig("S", pos, Vec3.ZERO, 0.8, 0.3, 1.0));
        m.enqueueInput("S", input, 0, input.length);
        return m.renderBlock("L", input.length);
    }

    private static void assertDifferent(float[] a, float[] b) {
        boolean any = false;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                any = true;
                break;
            }
        }
        assertTrue(any, "expected outputs to differ");
    }

    private static double energy(float[] x) {
        double e = 0;
        for (float v : x) {
            e += (double) v * v;
        }
        return e;
    }
}
