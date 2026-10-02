package com.github.huchangjun118.spatialaudiomixerjava.config;

import com.github.huchangjun118.spatialaudiomixerjava.math.Vector3f;

/**
 * Immutable configuration for one audio source.
 *
 * @param position      world-space position (x=right, y=up, z=forward)
 * @param gain          linear post-attenuation gain (0..1+)
 * @param refDistance   distance at which attenuation starts (gain = 1 below)
 * @param maxDistance   distance at/after which the source is fully inaudible
 * @param rolloff       attenuation steepness (higher = fades faster)
 * @param reverbSend    fraction of the dry signal fed into the reverb (0..1)
 */
public record SourceConfig(
        Vector3f position,
        float gain,
        float refDistance,
        float maxDistance,
        float rolloff,
        float reverbSend) {

    public SourceConfig {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        if (Float.isNaN(gain) || gain < 0f) {
            throw new IllegalArgumentException("gain must be >= 0: " + gain);
        }
        if (Float.isNaN(refDistance) || refDistance <= 0f) {
            throw new IllegalArgumentException("refDistance must be > 0: " + refDistance);
        }
        if (Float.isNaN(maxDistance) || maxDistance <= refDistance) {
            throw new IllegalArgumentException("maxDistance must be > refDistance: " + maxDistance);
        }
        if (Float.isNaN(rolloff) || rolloff < 0f) {
            throw new IllegalArgumentException("rolloff must be >= 0: " + rolloff);
        }
        if (Float.isNaN(reverbSend) || reverbSend < 0f || reverbSend > 1f) {
            throw new IllegalArgumentException("reverbSend must be in [0,1]: " + reverbSend);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Vector3f position = Vector3f.ZERO;
        private float gain = 1.0f;
        private float refDistance = 1.0f;
        private float maxDistance = 100.0f;
        private float rolloff = 1.0f;
        private float reverbSend = 0.2f;

        public Builder position(Vector3f p) { this.position = p; return this; }
        public Builder gain(float g) { this.gain = g; return this; }
        public Builder refDistance(float r) { this.refDistance = r; return this; }
        public Builder maxDistance(float m) { this.maxDistance = m; return this; }
        public Builder rolloff(float r) { this.rolloff = r; return this; }
        public Builder reverbSend(float s) { this.reverbSend = s; return this; }

        public SourceConfig build() {
            return new SourceConfig(position, gain, refDistance, maxDistance, rolloff, reverbSend);
        }
    }
}
