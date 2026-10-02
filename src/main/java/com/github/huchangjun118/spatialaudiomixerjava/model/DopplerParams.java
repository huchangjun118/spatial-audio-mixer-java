package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Immutable Doppler effect parameters. */
public record DopplerParams(boolean enabled, double speedOfSound, double maxShiftRatio) {
}
