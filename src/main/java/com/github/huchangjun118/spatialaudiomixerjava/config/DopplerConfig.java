package com.github.huchangjun118.spatialaudiomixerjava.config;

/**
 * Configuration for the Doppler effect.
 *
 * @param enabled       master switch; when false the dry signal passes through
 *                      with no delay or resampling (identical to baseline)
 * @param speedOfSound  speed of sound in m/s (default 343)
 */
public record DopplerConfig(boolean enabled, float speedOfSound) {

    public DopplerConfig {
        if (Float.isNaN(speedOfSound) || speedOfSound <= 0f) {
            throw new IllegalArgumentException("speedOfSound must be > 0: " + speedOfSound);
        }
    }

    public static DopplerConfig defaultEnabled() {
        return new DopplerConfig(true, 343f);
    }

    public static DopplerConfig disabled() {
        return new DopplerConfig(false, 343f);
    }

    public DopplerConfig withEnabled(boolean e) {
        return new DopplerConfig(e, speedOfSound);
    }
}
