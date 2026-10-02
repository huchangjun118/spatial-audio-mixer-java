package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.config.ListenerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-listener render state: a set of {@link SourceRuntime}s keyed by source
 * id. Runtimes for sources that disappeared from the snapshot are discarded
 * (along with their reverb tails), so a removed source never leaves residual
 * sound.
 *
 * <p>{@link #renderBlock} is synchronized: concurrent render calls for the
 * same listener are serialized. Different listeners render independently.
 */
public final class ListenerRuntime {

    private final String listenerId;
    private final Map<String, SourceRuntime> sourceRuntimes = new ConcurrentHashMap<>();
    private long frameCounter;

    public ListenerRuntime(String listenerId) {
        this.listenerId = listenerId;
    }

    public String listenerId() {
        return listenerId;
    }

    /**
     * Renders one block for this listener.
     *
     * @return a fresh channels x blockSize buffer; callers isolate failures
     *         (this method throws if the listener is no longer in the snapshot)
     */
    public synchronized float[][] renderBlock(Snapshot snapshot, DegradationLevel level) {
        int channels = snapshot.format().channelCount();
        int blockSize = snapshot.format().blockSize();
        float[][] out = new float[channels][blockSize];

        // Drop runtimes of sources removed from the snapshot (no tails).
        prune(snapshot);

        if (level.isMuted()) {
            return out;
        }

        ListenerConfig listenerConfig = snapshot.listeners().get(listenerId).config();
        if (!isFinite(listenerConfig.position()) || !isFinite(listenerConfig.forward())
                || !isFinite(listenerConfig.up()) || !Float.isFinite(listenerConfig.gain())) {
            throw new IllegalArgumentException(
                    "listener " + listenerId + " has non-finite parameters");
        }
        boolean halfRate = level.isHalfRate();
        boolean reverbOn = level.isReverbEnabled() && snapshot.mixerConfig().reverb().enabled();
        boolean dopplerOn = snapshot.mixerConfig().doppler().enabled();

        int maxDelaySamples = maxDelaySamples(snapshot);

        for (Snapshot.SourceEntry entry : snapshot.sources().values()) {
            SourceRuntime rt = sourceRuntimes.computeIfAbsent(entry.id(),
                    id -> new SourceRuntime(id, entry.source(), entry.config(),
                            snapshot.mixerConfig(), maxDelaySamples));
            rt.updateConfig(entry.config());
            for (int i = 0; i < blockSize; i++) {
                rt.renderFrame(out, frameCounter + i, halfRate,
                        listenerConfig.position(), listenerConfig.forward(), listenerConfig.up(),
                        listenerConfig.gain(), reverbOn, dopplerOn);
            }
        }

        // Output limiter: hard clamp to [-1, 1], deterministic.
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < blockSize; i++) {
                float s = out[ch][i];
                if (s > 1f) {
                    out[ch][i] = 1f;
                } else if (s < -1f) {
                    out[ch][i] = -1f;
                }
            }
        }

        frameCounter += blockSize;
        return out;
    }

    private static boolean isFinite(com.github.huchangjun118.spatialaudiomixerjava.math.Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z);
    }

    private void prune(Snapshot snapshot) {
        sourceRuntimes.keySet().removeIf(id -> !snapshot.sources().containsKey(id));
    }

    private static int maxDelaySamples(Snapshot snapshot) {
        float maxDistance = 1f;
        for (Snapshot.SourceEntry e : snapshot.sources().values()) {
            maxDistance = Math.max(maxDistance, e.config().maxDistance());
        }
        double speedOfSound = snapshot.mixerConfig().doppler().speedOfSound();
        int sampleRate = snapshot.format().sampleRate();
        return (int) (maxDistance / speedOfSound * sampleRate) + 1;
    }
}
