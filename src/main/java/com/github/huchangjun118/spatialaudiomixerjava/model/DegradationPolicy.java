package com.github.huchangjun118.spatialaudiomixerjava.model;

/**
 * Immutable overload degradation policy.
 *
 * @param mode                strategy once the per-block cost budget is exceeded
 * @param downsampleFactor    hold factor used by {@link DegradationMode#DOWNSAMPLE} (e.g. 2, 4)
 * @param maxActiveSources    number of nearest audible sources kept active by
 *                            {@link DegradationMode#LIMIT_SOURCES}
 * @param sourceCostUnits     cost units charged per audible source per internal block
 * @param capacityUnits       real-time cost budget per internal block (in the same units)
 */
public record DegradationPolicy(DegradationMode mode, int downsampleFactor, int maxActiveSources,
                                int sourceCostUnits, int capacityUnits) {

    public static DegradationPolicy unlimited(DegradationMode mode) {
        return new DegradationPolicy(mode, 2, Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
    }
}
