package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioFormat;
import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioSource;
import com.github.huchangjun118.spatialaudiomixerjava.config.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.SourceConfig;

import java.util.Map;

/**
 * An immutable, self-consistent snapshot of the mixer state. A render call
 * uses exactly one snapshot for its whole block, so concurrent updates can
 * never be observed mid-render.
 */
public final class Snapshot {

    public record SourceEntry(String id, AudioSource source, SourceConfig config) {
    }

    public record ListenerEntry(String id, ListenerConfig config) {
    }

    private final MixerConfig mixerConfig;
    private final Map<String, SourceEntry> sources;
    private final Map<String, ListenerEntry> listeners;
    private final long version;

    public Snapshot(MixerConfig mixerConfig,
                    Map<String, SourceEntry> sources,
                    Map<String, ListenerEntry> listeners,
                    long version) {
        this.mixerConfig = mixerConfig;
        this.sources = Map.copyOf(sources);
        this.listeners = Map.copyOf(listeners);
        this.version = version;
    }

    public MixerConfig mixerConfig() {
        return mixerConfig;
    }

    public AudioFormat format() {
        return mixerConfig.format();
    }

    public Map<String, SourceEntry> sources() {
        return sources;
    }

    public Map<String, ListenerEntry> listeners() {
        return listeners;
    }

    public long version() {
        return version;
    }

    public Snapshot withSources(Map<String, SourceEntry> newSources) {
        return new Snapshot(mixerConfig, newSources, listeners, version + 1);
    }

    public Snapshot withListeners(Map<String, ListenerEntry> newListeners) {
        return new Snapshot(mixerConfig, sources, newListeners, version + 1);
    }

    public Snapshot withMixerConfig(MixerConfig newConfig) {
        return new Snapshot(newConfig, sources, listeners, version + 1);
    }
}
