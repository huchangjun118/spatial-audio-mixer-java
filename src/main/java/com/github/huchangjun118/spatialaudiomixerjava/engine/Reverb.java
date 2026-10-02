package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.config.ReverbConfig;

/**
 * Per-source mono spatial reverb (Schroeder/Moorer style: comb filters in
 * parallel with damping, followed by all-pass filters in series). One
 * instance is owned per source per listener, so removing a source discards
 * its reverb state with it - no tails or crosstalk can leak between sources.
 *
 * <p>Sample-accurate and deterministic: no randomness, no block-local state.
 * Delay lengths are scaled with the sample rate.
 */
public final class Reverb {

    // Base delay lengths at 44.1 kHz (Freeverb-style prime-ish tunings).
    private static final int[] COMB_BASE = {1116, 1188, 1277, 1356};
    private static final int[] ALLPASS_BASE = {225, 556};
    private static final double ALLPASS_FEEDBACK = 0.5;

    private final int[] combLengths;
    private final double[][] combBuffers;
    private final int[] combIndices;
    private final double[] combLowpass;

    private final int[] allpassLengths;
    private final double[][] allpassBuffers;
    private final int[] allpassIndices;

    private final double feedback;
    private final double damp;
    private final double wetLevel;

    public Reverb(ReverbConfig config, int sampleRate) {
        double scale = sampleRate / 44100.0;

        int nCombs = COMB_BASE.length;
        this.combLengths = new int[nCombs];
        this.combBuffers = new double[nCombs][];
        this.combIndices = new int[nCombs];
        this.combLowpass = new double[nCombs];
        for (int i = 0; i < nCombs; i++) {
            combLengths[i] = Math.max(2, (int) Math.round(COMB_BASE[i] * scale));
            combBuffers[i] = new double[combLengths[i]];
        }

        int nAllpass = ALLPASS_BASE.length;
        this.allpassLengths = new int[nAllpass];
        this.allpassBuffers = new double[nAllpass][];
        this.allpassIndices = new int[nAllpass];
        for (int i = 0; i < nAllpass; i++) {
            allpassLengths[i] = Math.max(2, (int) Math.round(ALLPASS_BASE[i] * scale));
            allpassBuffers[i] = new double[allpassLengths[i]];
        }

        // roomSize 0..1 -> feedback 0.50..0.98
        this.feedback = 0.50 + 0.48 * config.roomSize();
        // damping 0..1 -> lowpass coefficient 0.05..0.60
        this.damp = 0.05 + 0.55 * config.damping();
        this.wetLevel = config.wetLevel();
    }

    /**
     * Processes one sample.
     *
     * @param input dry mono input sample
     * @return wet mono output sample (already scaled by wetLevel)
     */
    public float process(float input) {
        double in = input;
        double out = 0.0;

        for (int c = 0; c < combLengths.length; c++) {
            double[] buf = combBuffers[c];
            int idx = combIndices[c];
            double stored = buf[idx];
            double lp = combLowpass[c];
            // One-pole low-pass in the feedback loop (damping).
            lp = lp + (stored - lp) * damp;
            combLowpass[c] = lp;
            buf[idx] = in + lp * feedback;
            idx++;
            if (idx >= combLengths[c]) {
                idx = 0;
            }
            combIndices[c] = idx;
            out += stored;
        }
        out /= combLengths.length;

        for (int a = 0; a < allpassLengths.length; a++) {
            double[] buf = allpassBuffers[a];
            int idx = allpassIndices[a];
            double stored = buf[idx];
            double apOut = -out + stored;
            buf[idx] = out + apOut * ALLPASS_FEEDBACK;
            idx++;
            if (idx >= allpassLengths[a]) {
                idx = 0;
            }
            allpassIndices[a] = idx;
            out = apOut;
        }

        return (float) (out * wetLevel);
    }
}
