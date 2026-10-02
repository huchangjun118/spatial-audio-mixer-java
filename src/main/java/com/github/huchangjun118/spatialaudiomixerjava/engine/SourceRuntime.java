package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.model.SourceConfig;

/** Mutable runtime envelope of a source. The configuration reference is replaced atomically. */
final class SourceRuntime {

    private static final java.util.concurrent.atomic.AtomicLong SERIAL =
            new java.util.concurrent.atomic.AtomicLong();

    final long serial = SERIAL.incrementAndGet();
    final String uniqueKey;
    final InputRing ring;
    volatile SourceConfig config;
    volatile boolean removed;

    SourceRuntime(SourceConfig config, int ringCapacity) {
        this.config = config;
        this.uniqueKey = config.id() + "#" + serial;
        this.ring = new InputRing(ringCapacity);
    }

    String sourceId() {
        return config.id();
    }
}
