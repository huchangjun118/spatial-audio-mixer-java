package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Immutable spatial reverb parameters. */
public record ReverbParams(boolean enabled, double roomSize, double damping, double wetGain) {
}
