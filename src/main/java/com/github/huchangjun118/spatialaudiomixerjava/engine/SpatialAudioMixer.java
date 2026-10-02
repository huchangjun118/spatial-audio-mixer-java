package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioSource;
import com.github.huchangjun118.spatialaudiomixerjava.config.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.SourceConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side spatial audio mixer.
 *
 * <p><b>Snapshot consistency.</b> Management operations (add/update/remove
 * source and listener) are copy-on-write: each publishes a new immutable
 * {@link Snapshot}. A render call uses exactly one snapshot for a whole
 * block, so parameter updates can never be observed mid-render and every
 * output block corresponds to a self-consistent parameter snapshot.
 *
 * <p><b>Listener independence.</b> Each listener owns its own playback,
 * delay and reverb state, so listener outputs never interfere with each
 * other. A render failure (e.g. invalid listener orientation) is caught and
 * isolated to that listener: silence is returned and a failure counter is
 * incremented; other listeners are unaffected.
 *
 * <p><b>Determinism.</b> The signal path contains no randomness and no
 * wall-clock dependency; rendering the same input twice yields
 * sample-identical output. The only non-deterministic path is the automatic
 * degradation level selection, whose output at any fixed level is
 * deterministic.
 */
public final class SpatialAudioMixer {

    private final MixerConfig config;
    private volatile Snapshot snapshot;
    private final Map<String, ListenerRuntime> listenerRuntimes = new ConcurrentHashMap<>();
    private final DegradationController degradationController;

    private long renderBlocks;
    private long listenerFailures;

    public SpatialAudioMixer(MixerConfig config) {
        this.config = config;
        this.snapshot = new Snapshot(config, Map.of(), Map.of(), 0);
        this.degradationController = new DegradationController(
                config.degradation(), config.format().blockDurationSeconds());
    }

    public MixerConfig config() {
        return config;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public DegradationLevel degradationLevel() {
        return degradationController.currentLevel();
    }

    public long renderBlockCount() {
        return renderBlocks;
    }

    public long listenerFailureCount() {
        return listenerFailures;
    }

    // --- Management (copy-on-write) ----------------------------------------

    public synchronized void addSource(String id, AudioSource source, SourceConfig sourceConfig) {
        if (id == null || source == null || sourceConfig == null) {
            throw new IllegalArgumentException("id, source and sourceConfig must not be null");
        }
        Snapshot old = this.snapshot;
        Map<String, Snapshot.SourceEntry> sources = new LinkedHashMap<>(old.sources());
        sources.put(id, new Snapshot.SourceEntry(id, source, sourceConfig));
        this.snapshot = old.withSources(sources);
    }

    public synchronized void updateSource(String id, SourceConfig sourceConfig) {
        if (id == null || sourceConfig == null) {
            throw new IllegalArgumentException("id and sourceConfig must not be null");
        }
        Snapshot old = this.snapshot;
        Snapshot.SourceEntry existing = old.sources().get(id);
        if (existing == null) {
            throw new IllegalArgumentException("unknown source: " + id);
        }
        Map<String, Snapshot.SourceEntry> sources = new LinkedHashMap<>(old.sources());
        sources.put(id, new Snapshot.SourceEntry(id, existing.source(), sourceConfig));
        this.snapshot = old.withSources(sources);
    }

    public synchronized void removeSource(String id) {
        Snapshot old = this.snapshot;
        if (!old.sources().containsKey(id)) {
            return;
        }
        Map<String, Snapshot.SourceEntry> sources = new LinkedHashMap<>(old.sources());
        sources.remove(id);
        this.snapshot = old.withSources(sources);
    }

    public synchronized void addListener(String id, ListenerConfig listenerConfig) {
        if (id == null || listenerConfig == null) {
            throw new IllegalArgumentException("id and listenerConfig must not be null");
        }
        Snapshot old = this.snapshot;
        Map<String, Snapshot.ListenerEntry> listeners = new LinkedHashMap<>(old.listeners());
        listeners.put(id, new Snapshot.ListenerEntry(id, listenerConfig));
        this.snapshot = old.withListeners(listeners);
        listenerRuntimes.put(id, new ListenerRuntime(id));
    }

    public synchronized void updateListener(String id, ListenerConfig listenerConfig) {
        if (id == null || listenerConfig == null) {
            throw new IllegalArgumentException("id and listenerConfig must not be null");
        }
        Snapshot old = this.snapshot;
        if (!old.listeners().containsKey(id)) {
            throw new IllegalArgumentException("unknown listener: " + id);
        }
        Map<String, Snapshot.ListenerEntry> listeners = new LinkedHashMap<>(old.listeners());
        listeners.put(id, new Snapshot.ListenerEntry(id, listenerConfig));
        this.snapshot = old.withListeners(listeners);
    }

    public synchronized void removeListener(String id) {
        Snapshot old = this.snapshot;
        if (!old.listeners().containsKey(id)) {
            return;
        }
        Map<String, Snapshot.ListenerEntry> listeners = new LinkedHashMap<>(old.listeners());
        listeners.remove(id);
        this.snapshot = old.withListeners(listeners);
        listenerRuntimes.remove(id);
    }

    public synchronized void reset() {
        this.snapshot = new Snapshot(config, Map.of(), Map.of(), snapshot.version() + 1);
        listenerRuntimes.clear();
    }

    // --- Runtime effect toggles --------------------------------------------

    /** Enables or disables Doppler at runtime (takes effect next block). */
    public synchronized void setDopplerEnabled(boolean enabled) {
        Snapshot old = this.snapshot;
        MixerConfig mc = old.mixerConfig();
        this.snapshot = old.withMixerConfig(new MixerConfig(
                mc.format(), mc.doppler().withEnabled(enabled), mc.reverb(), mc.degradation()));
    }

    /** Enables or disables reverb at runtime (takes effect next block). */
    public synchronized void setReverbEnabled(boolean enabled) {
        Snapshot old = this.snapshot;
        MixerConfig mc = old.mixerConfig();
        this.snapshot = old.withMixerConfig(new MixerConfig(
                mc.format(), mc.doppler(), mc.reverb().withEnabled(enabled), mc.degradation()));
    }

    // --- Rendering ----------------------------------------------------------

    /**
     * Renders one block for the given listener. Never throws: a failure is
     * logged and isolated to this listener (silence is returned).
     */
    public float[][] renderBlock(String listenerId) {
        Snapshot snap = this.snapshot;
        ListenerRuntime runtime = listenerRuntimes.get(listenerId);
        DegradationLevel level = degradationController.currentLevel();

        long t0 = System.nanoTime();
        float[][] out;
        try {
            if (runtime == null || !snap.listeners().containsKey(listenerId)) {
                throw new IllegalArgumentException("unknown listener: " + listenerId);
            }
            out = runtime.renderBlock(snap, level);
        } catch (RuntimeException e) {
            listenerFailures++;
            out = new float[config.format().channelCount()][config.format().blockSize()];
        }
        long t1 = System.nanoTime();
        degradationController.recordBlock(t1 - t0);
        renderBlocks++;
        return out;
    }
}
