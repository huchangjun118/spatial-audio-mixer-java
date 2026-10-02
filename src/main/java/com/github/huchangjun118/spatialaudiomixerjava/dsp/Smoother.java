package com.github.huchangjun118.spatialaudiomixerjava.dsp;

/**
 * Per-sample one-pole value smoother.
 *
 * <p>The smoother advances exactly one step per processed sample/tick and is a pure
 * function of its previous state and the sequence of targets. It has no notion of
 * "block boundaries", which is what makes split-block rendering bit-identical to
 * one-shot rendering for identical parameter timelines.</p>
 */
public final class Smoother {

    private double value;

    public Smoother(double initial) {
        this.value = initial;
    }

    public double current() {
        return value;
    }

    /** Immediately assign a value (used when state is created or resynchronised). */
    public void set(double v) {
        this.value = v;
    }

    /**
     * Advance one step towards {@code target}.
     *
     * @param target desired value
     * @param rate   time constant in samples (tau); {@code <= 0} means instant snap
     * @return smoothed value after this step
     */
    public double process(double target, double rate) {
        if (rate <= 0.0) {
            value = target;
        } else {
            double alpha = -Math.expm1(-1.0 / rate);
            value += (target - value) * alpha;
        }
        return value;
    }
}
