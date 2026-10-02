package com.github.huchangjun118.spatialaudiomixerjava.engine;

/**
 * Overload degradation levels, ordered from highest to lowest quality.
 */
public enum DegradationLevel {

    /** Full quality: all effects processed at the configured sample rate. */
    NORMAL("normal", "full quality"),
    /** Reverb processing skipped (wet path muted). */
    REVERB_OFF("reverb-off", "reverb skipped to reduce load"),
    /** Rendering at half sample rate with sample-and-hold interpolation. */
    HALF_RATE("half-rate", "half sample rate rendering"),
    /** Output muted to silence. */
    MUTED("muted", "output muted due to overload");

    private final String code;
    private final String description;

    DegradationLevel(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public boolean isReverbEnabled() {
        return this == NORMAL;
    }

    public boolean isHalfRate() {
        return this == HALF_RATE;
    }

    public boolean isMuted() {
        return this == MUTED;
    }
}
