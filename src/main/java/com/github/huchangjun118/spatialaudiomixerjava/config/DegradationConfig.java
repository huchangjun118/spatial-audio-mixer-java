package com.github.huchangjun118.spatialaudiomixerjava.config;

import com.github.huchangjun118.spatialaudiomixerjava.engine.DegradationLevel;

/**
 * Configuration for the overload degradation controller.
 *
 * <p>The controller measures wall-clock render time per block and compares it
 * with the real-time budget ({@code blockSize / sampleRate}). When the ratio
 * stays above {@code escalateRatio} for {@code escalateBlocks} consecutive
 * blocks it escalates one level; it recovers stepwise after
 * {@code recoverBlocks} consecutive under-budget blocks.
 *
 * @param enabled         when false the level is always {@link DegradationLevel#NORMAL}
 * @param budgetFactor    fraction of the real-time budget considered affordable
 *                        (e.g. 0.8 = render may use up to 80% of the budget)
 * @param escalateRatio   load ratio that triggers escalation (e.g. 1.2)
 * @param recoverRatio    load ratio that allows recovery (e.g. 0.7)
 * @param escalateBlocks  consecutive over-budget blocks before escalating
 * @param recoverBlocks   consecutive under-budget blocks before recovering
 * @param forcedLevel     when non-null, degradation is pinned to this level
 *                        (deterministic, useful for tests)
 */
public record DegradationConfig(
        boolean enabled,
        double budgetFactor,
        double escalateRatio,
        double recoverRatio,
        int escalateBlocks,
        int recoverBlocks,
        DegradationLevel forcedLevel) {

    public DegradationConfig {
        if (budgetFactor <= 0) {
            throw new IllegalArgumentException("budgetFactor must be > 0: " + budgetFactor);
        }
        if (escalateRatio <= 1.0) {
            throw new IllegalArgumentException("escalateRatio must be > 1: " + escalateRatio);
        }
        if (recoverRatio <= 0 || recoverRatio >= escalateRatio) {
            throw new IllegalArgumentException("recoverRatio must be in (0, escalateRatio): " + recoverRatio);
        }
        if (escalateBlocks <= 0) {
            throw new IllegalArgumentException("escalateBlocks must be > 0: " + escalateBlocks);
        }
        if (recoverBlocks <= 0) {
            throw new IllegalArgumentException("recoverBlocks must be > 0: " + recoverBlocks);
        }
    }

    public static DegradationConfig defaultConfig() {
        return new DegradationConfig(true, 0.8, 1.2, 0.7, 3, 10, null);
    }

    public static DegradationConfig disabled() {
        return new DegradationConfig(false, 0.8, 1.2, 0.7, 3, 10, null);
    }

    public static DegradationConfig forced(DegradationLevel level) {
        return new DegradationConfig(true, 0.8, 1.2, 0.7, 3, 10, level);
    }
}
