package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.dsp.Smoother;
import com.github.huchangjun118.spatialaudiomixerjava.model.ListenerConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable runtime envelope of a listener. All render state is guarded by {@code lock}. */
final class ListenerRuntime {

    final Object lock = new Object();
    final String id;
    volatile ListenerConfig config;
    final Map<String, PairState> pairs = new LinkedHashMap<>();
    long clock;
    final Smoother muteSmoother = new Smoother(1.0);
    boolean removed;

    ListenerRuntime(ListenerConfig config) {
        this.id = config.id();
        this.config = config;
    }
}
