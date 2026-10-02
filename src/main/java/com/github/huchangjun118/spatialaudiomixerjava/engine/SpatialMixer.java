package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.dsp.ReverbTank;
import com.github.huchangjun118.spatialaudiomixerjava.dsp.Smoother;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core server-side spatial audio mixing engine.
 *
 * <h2>Spatial model</h2>
 * Each source contributes, per listener, a mono stream shaped by:
 * <ul>
 *   <li>distance attenuation: unity up to {@code referenceDistance}, then inverse
 *       distance, multiplied by a raised-cosine edge so it reaches exactly zero at
 *       {@code maxAudibleDistance} without a discontinuity;</li>
 *   <li>equal-power stereo panning from the angle of the source around the
 *       listener's forward axis (+Z; +X pans right). MONO layout applies no pan;</li>
 *   <li>optional Doppler playback-ratio resampling driven by the radial component
 *       of the relative source/listener velocity, clamped to a configurable bound;</li>
 *   <li>optional per-source spatial reverb (4 comb + 2 all-pass Schroeder tank),
 *       wet amount scaled by source reverb-send and distance gain;</li>
 *   <li>the source's and listener's linear gains.</li>
 * </ul>
 *
 * <h2>Determinism and block boundaries</h2>
 * All smoothing and effect state advances exactly one step per output DSP tick;
 * parameter snapshots are latched on a fixed sample-clock grid ({@code blockSize}),
 * never at call boundaries. Rendering the same input timeline in one call or split
 * across arbitrary call sizes therefore yields bit-identical samples. Summation
 * iterates sources in ascending id order into double accumulators, and every
 * output is hard-clipped to [-1, 1].
 *
 * <h2>Concurrency</h2>
 * Configurations are immutable and published via volatile reference; every grid
 * block renders from one immutable snapshot, so half-updated state is never
 * observed. Input ring writes are serialised per source; each listener's render
 * state is guarded by its own lock and listeners are mutually independent.
 *
 * <h2>Overload</h2>
 * Block cost is {@code audibleSources * sourceCostUnits}; exceeding
 * {@code capacityUnits} triggers the configured deterministic strategy:
 * {@code LIMIT_SOURCES} (nearest sources only, others ramp to silence),
 * {@code DOWNSAMPLE} (DSP advances every {@code downsampleFactor} samples, values
 * are sample-held in between), or {@code MUTE} (all DSP is skipped and the output
 * ramps softly to silence). Missing input is treated as silence. Rendering never
 * blocks or throws because of load.
 */
public final class SpatialMixer {

    /** Ramp length in samples applied when a source is removed. */
    public static final int DEATH_FADE_SAMPLES = 512;

    private volatile MixerConfig config;
    private final ConcurrentHashMap<String, SourceRuntime> sources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ListenerRuntime> listeners = new ConcurrentHashMap<>();
    /** Test hook: listeners whose render deterministically fails. */
    private final java.util.Set<String> faultyListeners =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Test hook: force one listener's renders onto the contained-failure path. */
    public void setFaultInjected(String listenerId, boolean value) {
        if (value) {
            faultyListeners.add(listenerId);
        } else {
            faultyListeners.remove(listenerId);
        }
    }

    public SpatialMixer(MixerConfig config) {
        this.config = validate(config);
    }

    private static MixerConfig validate(MixerConfig c) {
        if (c.sampleRate() <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (c.blockSize() <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        if (c.maxAudibleDistance() <= 0 || c.referenceDistance() <= 0
                || c.referenceDistance() >= c.maxAudibleDistance()) {
            throw new IllegalArgumentException("invalid audible distance range");
        }
        if (c.degradationPolicy().downsampleFactor() < 1) {
            throw new IllegalArgumentException("downsampleFactor must be >= 1");
        }
        return c;
    }

    public MixerConfig getConfig() {
        return config;
    }

    /**
     * Replace the configuration. Rate/block-size/layout changes are structural and
     * deterministically reset all per-listener render state; effect/enable-only
     * changes need no reset.
     */
    public void updateConfig(MixerConfig next) {
        validate(next);
        MixerConfig old = this.config;
        this.config = next;
        boolean structural = old.sampleRate() != next.sampleRate()
                || old.blockSize() != next.blockSize()
                || old.layout() != next.layout();
        if (structural) {
            for (ListenerRuntime lr : listeners.values()) {
                synchronized (lr.lock) {
                    lr.pairs.clear();
                    lr.clock = 0;
                    lr.muteSmoother.set(1.0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ sources

    public void addSource(SourceConfig cfg) {
        if (cfg == null || cfg.id() == null || cfg.id().isBlank()) {
            throw new IllegalArgumentException("source id required");
        }
        sources.compute(cfg.id(), (id, existing) -> {
            if (existing != null && !existing.removed) {
                throw new IllegalStateException("source already exists: " + id);
            }
            return new SourceRuntime(cfg, config.sampleRate());
        });
    }

    public void updateSource(SourceConfig cfg) {
        if (cfg == null || cfg.id() == null) {
            throw new IllegalArgumentException("source config required");
        }
        SourceRuntime sr = sources.get(cfg.id());
        if (sr == null) {
            throw new NoSuchElementException("no such source: " + cfg.id());
        }
        sr.config = cfg; // single atomic publication of an immutable object
    }

    /**
     * Remove a source. Its per-listener render state fades out over
     * {@link #DEATH_FADE_SAMPLES} samples and is then discarded together with the
     * reverb tank, so no reverb tail or crosstalk can remain.
     */
    public void removeSource(String sourceId) {
        SourceRuntime sr = sources.remove(sourceId);
        if (sr != null) {
            sr.removed = true;
        }
    }

    // ---------------------------------------------------------------- listeners

    public void addListener(ListenerConfig cfg) {
        if (cfg == null || cfg.id() == null || cfg.id().isBlank()) {
            throw new IllegalArgumentException("listener id required");
        }
        listeners.compute(cfg.id(), (id, existing) -> {
            if (existing != null && !existing.removed) {
                throw new IllegalStateException("listener already exists: " + id);
            }
            return new ListenerRuntime(cfg);
        });
    }

    public void updateListener(ListenerConfig cfg) {
        if (cfg == null || cfg.id() == null) {
            throw new IllegalArgumentException("listener config required");
        }
        ListenerRuntime lr = listeners.get(cfg.id());
        if (lr == null) {
            throw new NoSuchElementException("no such listener: " + cfg.id());
        }
        lr.config = cfg;
    }

    public void removeListener(String listenerId) {
        ListenerRuntime lr = listeners.remove(listenerId);
        if (lr != null) {
            synchronized (lr.lock) {
                lr.removed = true;
                lr.pairs.clear();
            }
        }
    }

    // -------------------------------------------------------------------- input

    /**
     * Append mono input for a source. Never blocks: a full ring overwrites the
     * oldest samples and counts them as dropped (see {@link #getDroppedSamples}).
     */
    public void enqueueInput(String sourceId, float[] samples, int off, int len) {
        if (samples == null || len <= 0) {
            return;
        }
        if (off < 0 || len < 0 || off + len > samples.length) {
            throw new IndexOutOfBoundsException("invalid input range");
        }
        SourceRuntime sr = sources.get(sourceId);
        if (sr == null) {
            throw new NoSuchElementException("no such source: " + sourceId);
        }
        synchronized (sr.ring) {
            sr.ring.write(samples, off, len);
        }
    }

    public int getDroppedSamples(String sourceId) {
        SourceRuntime sr = sources.get(sourceId);
        return sr == null ? 0 : sr.ring.droppedSamples();
    }

    // ------------------------------------------------------------------ rendering


    /** Render the next {@code samples} output samples for one listener. */
    public RenderResult renderBlock(String listenerId, int samples) {
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be positive");
        }
        ListenerRuntime lr = listeners.get(listenerId);
        if (lr == null) {
            throw new NoSuchElementException("no such listener: " + listenerId);
        }
        try {
            if (faultyListeners.contains(listenerId)) {
                throw new IllegalStateException("injected render fault");
            }
            synchronized (lr.lock) {
                return renderLocked(lr, samples);
            }
        } catch (RuntimeException e) {
            // Contain any failure: deterministic silence result, never propagates.
            return new RenderResult(listenerId, silent(config.layout().channels(), samples),
                    samples, false, null, e.toString());
        }
    }

    /** Render for all current listeners (sorted by id); one failure never affects others. */
    public List<RenderResult> renderAll(int samples) {
        List<ListenerRuntime> snapshot = new ArrayList<>(listeners.values());
        snapshot.sort(Comparator.comparing(l -> l.id));
        List<RenderResult> out = new ArrayList<>(snapshot.size());
        for (ListenerRuntime lr : snapshot) {
            out.add(renderBlock(lr.id, samples));
        }
        return out;
    }

    private RenderResult renderLocked(ListenerRuntime lr, int requested) {
        MixerConfig cfg = config;
        int ch = cfg.layout().channels();
        float[][] out = new float[ch][requested];
        boolean degradedFlag = false;
        DegradationMode modeUsed = null;

        int produced = 0;
        while (produced < requested) {
            long clock = lr.clock;
            int blockSize = cfg.blockSize();
            long gridStart = (clock / blockSize) * blockSize;
            int n = Math.min(blockSize - (int) (clock - gridStart), requested - produced);

            // Immutable snapshot for the remainder of this grid block.
            List<SourceRuntime> live = new ArrayList<>(sources.values());
            live.sort(Comparator.comparing(SourceRuntime::sourceId));

            SegmentPlan plan = planSegment(cfg, lr, live);
            if (plan.overloaded) {
                degradedFlag = true;
                modeUsed = plan.mode;
            }
            renderSegment(cfg, lr, plan, out, produced, n);
            purgeFinished(lr);
            lr.clock += n;
            produced += n;
        }
        return new RenderResult(lr.id, out, requested, degradedFlag, modeUsed, null);
    }

    private static final class SegmentPlan {
        final List<SourceRuntime> dspSources = new ArrayList<>();
        final List<SourceRuntime> fadingSources = new ArrayList<>();
        final Set<String> selectedKeys = new HashSet<>();
        final boolean overloaded;
        final DegradationMode mode;
        int hold = 1;

        SegmentPlan(boolean overloaded, DegradationMode mode) {
            this.overloaded = overloaded;
            this.mode = mode;
        }
    }

    private SegmentPlan planSegment(MixerConfig cfg, ListenerRuntime lr,
                                    List<SourceRuntime> live) {
        DegradationPolicy pol = cfg.degradationPolicy();
        ListenerConfig lc = lr.config;
        int audible = 0;
        for (SourceRuntime sr : live) {
            SourceConfig sc = sr.config;
            if (distance(sc.position(), lc.position()) < cfg.maxAudibleDistance()
                    && sc.gain() > 0.0) {
                audible++;
            }
        }
        boolean overloaded = (long) audible * pol.sourceCostUnits() > pol.capacityUnits();
        SegmentPlan plan = new SegmentPlan(overloaded, overloaded ? pol.mode() : null);

        List<SourceRuntime> selected = live;
        if (overloaded && plan.mode == DegradationMode.LIMIT_SOURCES) {
            int k = Math.max(0, pol.maxActiveSources());
            List<SourceRuntime> byDistance = new ArrayList<>(live);
            byDistance.sort(Comparator
                    .comparingDouble((SourceRuntime sr) ->
                            distance(sr.config.position(), lc.position()))
                    .thenComparing(SourceRuntime::sourceId));
            selected = byDistance.subList(0, Math.min(k, byDistance.size()));
        }
        if (overloaded && plan.mode == DegradationMode.DOWNSAMPLE) {
            plan.hold = Math.max(2, pol.downsampleFactor());
        }

        Set<String> selectedIds = new HashSet<>();
        for (SourceRuntime sr : selected) {
            selectedIds.add(sr.uniqueKey);
            plan.dspSources.add(sr);
            plan.selectedKeys.add(sr.uniqueKey);
        }
        for (SourceRuntime sr : live) {
            if (!selectedIds.contains(sr.uniqueKey)) {
                plan.fadingSources.add(sr);
            }
        }
        // Removed sources still fading out on this listener keep rendering (dry+wet),
        // so the tail ends coherently; once their fade completes they are purged.
        for (PairState ps : lr.pairs.values()) {
            if (ps.dying && ps.ageSamples < DEATH_FADE_SAMPLES
                    && !plan.selectedKeys.contains(ps.source.uniqueKey)) {
                plan.dspSources.add(ps.source);
                plan.selectedKeys.add(ps.source.uniqueKey);
            }
        }
        return plan;
    }

    private void renderSegment(MixerConfig cfg, ListenerRuntime lr, SegmentPlan plan,
                               float[][] out, int outOff, int n) {
        ListenerConfig lc = lr.config;
        DopplerParams dop = cfg.doppler();
        ReverbParams rev = cfg.reverb();
        boolean dopplerOn = dop.enabled();
        boolean reverbOn = rev.enabled();
        boolean muteAll = plan.overloaded && plan.mode == DegradationMode.MUTE;
        int hold = plan.hold;
        double rate = cfg.smoothingRate();
        double wetGlobal = reverbOn ? Math.max(0.0, rev.wetGain()) : 0.0;

        for (int i = 0; i < n; i++) {
            long clock = lr.clock + i;
            double accL = 0.0;
            double accR = 0.0;
            // Sample-hold phase is locked to the absolute sample clock, never to the
            // call's local index, so arbitrary block splits stay bit-identical.
            boolean advanceTick = (clock % hold) == 0;

            // Sources that receive no DSP this block (mute, limit, or held samples):
            // their gains still ramp smoothly so no click is ever produced.
            if (!muteAll) {
                for (SourceRuntime sr : plan.fadingSources) {
                    PairState ps = lr.pairs.get(sr.uniqueKey);
                    double g = ps == null ? 0.0 : ps.gainSmoother.process(0.0, rate);
                    double w = ps == null ? 0.0
                            : ps.wetSmoother.process(0.0, rate) * wetGlobal;
                    double[] spatial = computeSpatial(cfg, sr.config, lc);
                    double dry = (ps == null ? 0f : ps.lastDry) * g;
                    double wet = (ps == null ? 0f : ps.lastWet) * w;
                    accL += (dry + wet) * panGainLeft(spatial[1], cfg.layout());
                    accR += (dry + wet) * panGainRight(spatial[1], cfg.layout());
                }
            }

            if (!muteAll) {
                for (SourceRuntime sr : plan.dspSources) {
                    PairState ps = obtain(lr, sr, cfg);
                    SourceConfig sc = sr.config;
                    double[] spatial = computeSpatial(cfg, sc, lc);
                    double distGain = spatial[0];
                    double pan = spatial[1];

                    boolean held = !advanceTick;
                    double deathScale = 1.0;
                    if (ps.dying) {
                        deathScale = Math.max(0.0,
                                1.0 - (double) ps.ageSamples / DEATH_FADE_SAMPLES);
                    }
                    double targetDry = sc.gain() * lc.gain() * distGain * deathScale;
                    double targetWet = targetDry * Math.max(0.0, sc.reverbSend());

                    if (held) {
                        double g = ps.gainSmoother.current();
                        double w = ps.wetSmoother.current() * wetGlobal;
                        double dry = ps.lastDry * g;
                        double wet = ps.lastWet * w;
                        accL += (dry + wet) * panGainLeft(pan, cfg.layout());
                        accR += (dry + wet) * panGainRight(pan, cfg.layout());
                        continue;
                    }

                    double ratio = 1.0;
                    if (dopplerOn) {
                        double radial = radialVelocity(sc, lc);
                        double max = Math.abs(dop.maxShiftRatio());
                        ratio = clamp((dop.speedOfSound()
                                + radial * sc.dopplerFactor()) / dop.speedOfSound(),
                                1.0 - max, 1.0 + max);
                    }
                    double smoothRatio = ps.ratioSmoother.process(ratio, rate);
                    double usedRatio = dopplerOn ? smoothRatio : 1.0;

                    // Resync on first tick or after a DSP gap (mute/downsample/held
                    // periods longer than one hold window): anchor to the present so
                    // no stale samples are replayed.
                    boolean resync = !ps.everAdvanced
                            || (ps.lastDspClock != Long.MIN_VALUE
                            && clock - ps.lastDspClock > hold);
                    final InputRing ring = sr.ring;
                    if (resync && ps.everAdvanced) {
                        // Re-anchor to the freshest available input after a DSP gap.
                        synchronized (ring) {
                            ps.inputBaseClock = Math.max(ps.inputBaseClock,
                                    ring.maxClock() - 1);
                        }
                    }
                    final long base = ps.inputBaseClock;
                    float inSample = ps.resampler.readAt(
                            c -> ringGet(ring, c - base), clock, usedRatio, resync);
                    ps.everAdvanced = true;

                    double g = ps.gainSmoother.process(targetDry, rate);
                    double dry = inSample * g;

                    float wetSample = 0.0f;
                    double w = 0.0;
                    if (reverbOn) {
                        if (ps.reverb == null || !ps.reverbWasEnabled) {
                            // Fresh tank (or re-enabled): start from a known zero
                            // state so no stale tail from a previous session emerges.
                            if (ps.reverb == null) {
                                ps.reverb = new ReverbTank(cfg.sampleRate());
                            } else {
                                ps.reverb.reset();
                            }
                        }
                        ps.reverbWasEnabled = true;
                        wetSample = ps.reverb.process(inSample, rev.roomSize(), rev.damping());
                        w = ps.wetSmoother.process(targetWet, rate) * wetGlobal;
                    } else {
                        ps.reverbWasEnabled = false;
                        ps.wetSmoother.process(0.0, rate);
                    }
                    double wet = wetSample * w;

                    ps.lastDry = inSample;
                    ps.lastWet = wetSample;
                    ps.ageSamples++;
                    ps.readClock = clock;
                    ps.lastDspClock = clock;

                    accL += (dry + wet) * panGainLeft(pan, cfg.layout());
                    accR += (dry + wet) * panGainRight(pan, cfg.layout());
                }
            }

            double muteGain = lr.muteSmoother.process(muteAll ? 0.0 : 1.0, rate);
            double sampleL = clamp(accL * muteGain, -1.0, 1.0);
            double sampleR = clamp(accR * muteGain, -1.0, 1.0);
            if (cfg.layout() == ChannelLayout.MONO) {
                out[0][outOff + i] = (float) sampleL;
            } else {
                out[0][outOff + i] = (float) sampleL;
                out[1][outOff + i] = (float) sampleR;
            }
        }
    }

    private PairState obtain(ListenerRuntime lr, SourceRuntime sr, MixerConfig cfg) {
        PairState ps = lr.pairs.get(sr.uniqueKey);
        if (ps == null) {
            // A source present from the start of a render timeline must pass
            // bit-identically; the smoother is seeded with the current target gain.
            // Entrance smoothness at the audible boundary comes from the raised-cosine
            // distance edge (and from per-sample gain ramping after parameter changes).
            double seed = sr.config.gain() * lr.config.gain();
            ps = new PairState((int) lr.clock, seed, false, cfg.sampleRate());
            ps.source = sr;
            // A source whose input starts at ring clock 0 beginning at output clock 0
            // is the canonical timeline. A source attached mid-stream anchors its
            // already-queued samples to "now", so late joining never reads silence.
            synchronized (sr.ring) {
                ps.inputBaseClock = lr.clock > 0
                        ? lr.clock - Math.max(0, sr.ring.maxClock() - 1) : 0L;
            }
            lr.pairs.put(sr.uniqueKey, ps);
        }
        if (sr.removed) {
            ps.dying = true;
        }
        return ps;
    }

    private void purgeFinished(ListenerRuntime lr) {
        lr.pairs.values().removeIf(ps -> ps.dying
                && ps.ageSamples >= DEATH_FADE_SAMPLES);
    }

    private static float ringGet(InputRing ring, long c) {
        synchronized (ring) {
            return ring.get(c);
        }
    }

    // ------------------------------------------------------------- spatial math

    private static double[] computeSpatial(MixerConfig cfg, SourceConfig sc, ListenerConfig lc) {
        double d = distance(sc.position(), lc.position());
        double gain;
        if (d >= cfg.maxAudibleDistance()) {
            gain = 0.0;
        } else if (d <= cfg.referenceDistance()) {
            gain = 1.0;
        } else {
            double norm = (d - cfg.referenceDistance())
                    / (cfg.maxAudibleDistance() - cfg.referenceDistance());
            double inv = cfg.referenceDistance()
                    / (cfg.referenceDistance()
                    + cfg.rolloffFactor() * (d - cfg.referenceDistance()));
            double edge = 0.5 + 0.5 * Math.cos(Math.PI * norm);
            gain = inv * edge;
        }
        double dx = sc.position().x() - lc.position().x();
        double dz = sc.position().z() - lc.position().z();
        double angle = Math.atan2(dx, dz);
        double pan = clamp(angle / (Math.PI / 2.0), -1.0, 1.0);
        return new double[]{gain, pan};
    }

    private static double panGainLeft(double pan, ChannelLayout layout) {
        if (layout == ChannelLayout.MONO) {
            return 1.0;
        }
        double p = (pan + 1.0) * 0.5;
        return Math.cos(p * Math.PI / 2.0);
    }

    private static double panGainRight(double pan, ChannelLayout layout) {
        if (layout == ChannelLayout.MONO) {
            return 0.0;
        }
        double p = (pan + 1.0) * 0.5;
        return Math.sin(p * Math.PI / 2.0);
    }

    /** Radial component of relative velocity; positive when source approaches listener. */
    private static double radialVelocity(SourceConfig sc, ListenerConfig lc) {
        double dx = sc.position().x() - lc.position().x();
        double dy = sc.position().y() - lc.position().y();
        double dz = sc.position().z() - lc.position().z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-9) {
            return 0.0;
        }
        double rvx = sc.velocity().x() - lc.velocity().x();
        double rvy = sc.velocity().y() - lc.velocity().y();
        double rvz = sc.velocity().z() - lc.velocity().z();
        return -(dx * rvx + dy * rvy + dz * rvz) / d;
    }

    private static double distance(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float[][] silent(int channels, int samples) {
        return new float[channels][samples];
    }
}
