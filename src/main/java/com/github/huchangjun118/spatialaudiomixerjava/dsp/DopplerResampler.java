package com.github.huchangjun118.spatialaudiomixerjava.dsp;

import java.util.function.LongToDoubleFunction;

/**
 * Variable playback-rate linear resampler used for Doppler pitch shift.
 *
 * <p>The resampler keeps a fractional read position {@code readPos} in source-sample
 * coordinates. Each output tick advances it by the current playback ratio and
 * returns a linear interpolation of the two surrounding source samples:</p>
 * <ul>
 *   <li>{@code ratio > 1}: source approached, playback faster, pitch up;</li>
 *   <li>{@code ratio < 1}: source receding, playback slower, pitch down.</li>
 * </ul>
 *
 * <p>State is a single double advanced by exactly one step per DSP tick, so
 * splitting a render into multiple blocks advances it by exactly the same amount
 * as one big block. When the ratio is exactly {@code 1.0} the raw source sample at
 * the integer clock is returned untouched (no interpolation arithmetic), which is
 * bit-identical to the no-Doppler baseline.</p>
 */
public final class DopplerResampler {

    private double readPos;

    public DopplerResampler(long initialClock) {
        this.readPos = initialClock;
    }

    public double position() {
        return readPos;
    }

    /**
     * Produce one output sample.
     *
     * @param reader absolute source-sample reader (returns {@code 0} outside buffered range)
     * @param clock  output tick clock
     * @param ratio  playback ratio (fractional input position advance per output sample)
     * @param resync when true, re-anchor the read position to {@code clock} before
     *               reading (used after a gap such as mute/downsample degradation or
     *               when a source first appears) — never guessed heuristically
     */
    public float readAt(LongToDoubleFunction reader, long clock, double ratio, boolean resync) {
        if (resync) {
            readPos = clock;
        }
        if (ratio == 1.0) {
            float v = (float) reader.applyAsDouble(clock);
            readPos = clock + 1.0;
            return v;
        }
        long i0 = (long) Math.floor(readPos);
        double frac = readPos - i0;
        double s0 = reader.applyAsDouble(i0);
        double s1 = reader.applyAsDouble(i0 + 1);
        float out = (float) (s0 + (s1 - s0) * frac);
        readPos += ratio;
        return out;
    }

    public void reset(long clock) {
        readPos = clock;
    }
}
