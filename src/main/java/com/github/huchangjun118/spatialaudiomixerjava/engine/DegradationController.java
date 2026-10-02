package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.config.DegradationConfig;

/**
 * Thread-safe overload degradation state machine.
 *
 * <p>Render wall-clock time is compared against the real-time budget
 * ({@code blockSize / sampleRate * budgetFactor}). After
 * {@code escalateBlocks} consecutive over-budget blocks the level steps up
 * (NORMAL -> REVERB_OFF -> HALF_RATE -> MUTED); after
 * {@code recoverBlocks} consecutive under-budget blocks it recovers one
 * step. A cooldown after entering {@link DegradationLevel#MUTED} prevents
 * fast mute/unmute oscillation (muted renders cost almost nothing).
 *
 * <p>All transitions are deterministic given the measured timings; the
 * resulting audio at each level is deterministic as well.
 */
public final class DegradationController {

    private final DegradationConfig config;
    private final double budgetNanos;

    private DegradationLevel level = DegradationLevel.NORMAL;
    private int overStreak;
    private int underStreak;
    private int muteCooldown;

    public DegradationController(DegradationConfig config, double blockDurationSeconds) {
        this.config = config;
        this.budgetNanos = blockDurationSeconds * 1_000_000_000.0 * config.budgetFactor();
    }

    public synchronized DegradationLevel currentLevel() {
        if (config.forcedLevel() != null) {
            return config.forcedLevel();
        }
        if (!config.enabled()) {
            return DegradationLevel.NORMAL;
        }
        return level;
    }

    /**
     * Reports the wall-clock time spent rendering one block.
     */
    public synchronized void recordBlock(long nanos) {
        if (config.forcedLevel() != null || !config.enabled()) {
            return;
        }
        if (level == DegradationLevel.MUTED) {
            // Muted renders cost ~nothing; enforce a cooldown before recovery.
            if (muteCooldown > 0) {
                muteCooldown--;
                overStreak = 0;
                underStreak = 0;
                return;
            }
        }

        double load = nanos / budgetNanos;
        if (load >= config.escalateRatio()) {
            overStreak++;
            underStreak = 0;
            if (overStreak >= config.escalateBlocks()) {
                escalate();
                overStreak = 0;
            }
        } else if (load <= config.recoverRatio()) {
            underStreak++;
            overStreak = 0;
            if (underStreak >= config.recoverBlocks()) {
                recover();
                underStreak = 0;
            }
        } else {
            overStreak = 0;
            underStreak = 0;
        }
    }

    private void escalate() {
        if (level == DegradationLevel.NORMAL) {
            level = DegradationLevel.REVERB_OFF;
        } else if (level == DegradationLevel.REVERB_OFF) {
            level = DegradationLevel.HALF_RATE;
        } else if (level == DegradationLevel.HALF_RATE) {
            level = DegradationLevel.MUTED;
            muteCooldown = config.recoverBlocks() * 4;
        }
    }

    private void recover() {
        if (level == DegradationLevel.REVERB_OFF) {
            level = DegradationLevel.NORMAL;
        } else if (level == DegradationLevel.HALF_RATE) {
            level = DegradationLevel.REVERB_OFF;
        } else if (level == DegradationLevel.MUTED) {
            level = DegradationLevel.HALF_RATE;
        }
    }
}
