package com.github.huchangjun118.spatialaudiomixerjava.config;

import com.github.huchangjun118.spatialaudiomixerjava.math.Vector3f;

/**
 * Immutable configuration for one listener.
 *
 * @param position world-space position
 * @param forward  facing direction (will be normalized)
 * @param up       up direction (will be normalized)
 * @param gain     master linear gain for this listener's output
 */
public record ListenerConfig(
        Vector3f position,
        Vector3f forward,
        Vector3f up,
        float gain) {

    public ListenerConfig {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        if (forward == null || forward.length() < 1e-6f) {
            throw new IllegalArgumentException("forward must be a non-zero vector");
        }
        if (up == null || up.length() < 1e-6f) {
            throw new IllegalArgumentException("up must be a non-zero vector");
        }
        if (Float.isNaN(gain) || gain < 0f) {
            throw new IllegalArgumentException("gain must be >= 0: " + gain);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Vector3f position = Vector3f.ZERO;
        private Vector3f forward = Vector3f.FORWARD;
        private Vector3f up = Vector3f.UP;
        private float gain = 1.0f;

        public Builder position(Vector3f p) { this.position = p; return this; }
        public Builder forward(Vector3f f) { this.forward = f; return this; }
        public Builder up(Vector3f u) { this.up = u; return this; }
        public Builder gain(float g) { this.gain = g; return this; }

        public ListenerConfig build() {
            return new ListenerConfig(position, forward, up, gain);
        }
    }
}
