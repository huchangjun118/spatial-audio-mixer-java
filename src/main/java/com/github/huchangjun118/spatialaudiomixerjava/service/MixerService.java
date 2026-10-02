package com.github.huchangjun118.spatialaudiomixerjava.service;

import com.github.huchangjun118.spatialaudiomixerjava.engine.SpatialMixer;
import com.github.huchangjun118.spatialaudiomixerjava.model.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.RenderResult;
import com.github.huchangjun118.spatialaudiomixerjava.model.SourceConfig;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring-managed facade over {@link SpatialMixer}. Methods are synchronised so the
 * engine itself can be created/replaced atomically; the engine performs its own
 * fine-grained concurrency control while rendering.
 */
@Service
public class MixerService {

    private SpatialMixer mixer = new SpatialMixer(MixerConfig.defaults());

    public synchronized SpatialMixer getMixer() {
        return mixer;
    }

    /** Replace the whole configuration (engine is rebuilt with reset DSP state). */
    public synchronized void configure(MixerConfig config) {
        this.mixer = new SpatialMixer(config);
    }

    public synchronized void addSource(SourceConfig config) {
        mixer.addSource(config);
    }

    public synchronized void updateSource(SourceConfig config) {
        mixer.updateSource(config);
    }

    public synchronized void removeSource(String sourceId) {
        mixer.removeSource(sourceId);
    }

    public synchronized void addListener(ListenerConfig config) {
        mixer.addListener(config);
    }

    public synchronized void updateListener(ListenerConfig config) {
        mixer.updateListener(config);
    }

    public synchronized void removeListener(String listenerId) {
        mixer.removeListener(listenerId);
    }

    /** @return number of samples dropped because the source ring overflowed */
    public synchronized int enqueueInput(String sourceId, float[] samples, int off, int len) {
        int before = mixer.getDroppedSamples(sourceId);
        mixer.enqueueInput(sourceId, samples, off, len);
        return mixer.getDroppedSamples(sourceId) - before;
    }

    /** Render one block for every registered listener. */
    public List<RenderResult> renderAll(int samples) {
        SpatialMixer m;
        synchronized (this) {
            m = mixer;
        }
        return m.renderAll(samples);
    }
}
