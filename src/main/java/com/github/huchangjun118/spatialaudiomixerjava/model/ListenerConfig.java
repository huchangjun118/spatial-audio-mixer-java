package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Immutable configuration of one listener. */
public record ListenerConfig(String id, Vec3 position, Vec3 velocity, double gain) {
}
