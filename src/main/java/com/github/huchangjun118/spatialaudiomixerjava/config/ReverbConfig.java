package com.github.huchangjun118.spatialaudiomixerjava.config;

/**
 * Configuration for the spatial reverb effect.
 *
 * @param enabled  master switch; when false the wet path is bypassed and the
 *                 output is identical to a mixer with reverb disabled
 * @param roomSize 0..1, controls reverb decay time (larger = longer tail)
 * @param damping  0..1, high-frequency damping in the feedback loop
 * @param wetLevel 0..1, output level of the wet (reverb) signal
 */
public record ReverbConfig(
        boolean enabled,
        float roomSize,
        float damping,
        float wetLevel) {

    public ReverbConfig {
        if (Float.isNaN(roomSize) || roomSize < 0f || roomSize > 1f) {
            throw new IllegalArgumentException("roomSize must be in [0,1]: " + roomSize);
        }
        if (Float.isNaN(damping) || damping < 0f || damping > 1f) {
            throw new IllegalArgumentException("damping must be in [0,1]: " + damping);
        }
        if (Float.isNaN(wetLevel) || wetLevel < 0f || wetLevel > 1f) {
            throw new IllegalArgumentException("wetLevel must be in [0,1]: " + wetLevel);
        }
    }

    public static ReverbConfig defaultEnabled() {
        return new ReverbConfig(true, 0.8f, 0.5f, 0.3f);
    }

    public static ReverbConfig disabled() {
        return new ReverbConfig(false, 0.8f, 0.5f, 0.3f);
    }

    public ReverbConfig withEnabled(boolean e) {
        return new ReverbConfig(e, roomSize, damping, wetLevel);
    }
}
