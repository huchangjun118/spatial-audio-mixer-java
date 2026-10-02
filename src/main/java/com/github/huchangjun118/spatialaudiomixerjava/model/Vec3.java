package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Immutable 3D vector. */
public record Vec3(double x, double y, double z) {
    public static Vec3 ZERO = new Vec3(0, 0, 0);
}
