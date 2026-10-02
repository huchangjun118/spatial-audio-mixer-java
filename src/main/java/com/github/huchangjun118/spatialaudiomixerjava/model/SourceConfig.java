package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Immutable configuration of one sound source. */
public record SourceConfig(String id, Vec3 position, Vec3 velocity, double gain,
                           double reverbSend, double dopplerFactor) {
}
