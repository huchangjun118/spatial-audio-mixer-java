package com.github.huchangjun118.spatialaudiomixerjava.dsp;

import java.util.Arrays;

/**
 * Schroeder-style mono reverb: four parallel feedback comb filters (each with a
 * one-pole damping low-pass) followed by two series all-pass diffusers.
 *
 * <p>All arithmetic is deterministic and stateful; {@link #reset()} restores the
 * exact all-zero state, so a removed source leaves no residual tail. The engine is
 * responsible for the wet mix and for bypassing this tank entirely when reverb is
 * disabled, which guarantees bit-identical output to a run where reverb was never
 * enabled.</p>
 */
public final class ReverbTank {

    private static final int[] COMB_LENGTHS_44K = {1116, 1188, 1277, 1356};
    private static final int[] ALLPASS_LENGTHS_44K = {556, 441};
    private static final double ALLPASS_GAIN = 0.5;

    private final double[][] comb;
    private final int[] combPos;
    private final double[] combFilter;
    private final double[][] allpass;
    private final int[] allpassPos;
    private final int combCount;
    private final int allpassCount;

    public ReverbTank(int sampleRate) {
        double scale = sampleRate / 44100.0;
        this.combCount = COMB_LENGTHS_44K.length;
        this.allpassCount = ALLPASS_LENGTHS_44K.length;
        this.comb = new double[combCount][];
        this.combPos = new int[combCount];
        this.combFilter = new double[combCount];
        for (int i = 0; i < combCount; i++) {
            int len = Math.max(2, (int) Math.round(COMB_LENGTHS_44K[i] * scale));
            this.comb[i] = new double[len];
        }
        this.allpass = new double[allpassCount][];
        this.allpassPos = new int[allpassCount];
        for (int i = 0; i < allpassCount; i++) {
            int len = Math.max(2, (int) Math.round(ALLPASS_LENGTHS_44K[i] * scale));
            this.allpass[i] = new double[len];
        }
    }

    /**
     * Process one mono sample.
     *
     * @param in       input sample
     * @param roomSize comb feedback in [0,1)
     * @param damping  low-pass damping in [0,1]
     * @return reverb-only (wet, unattenuated) sample
     */
    public float process(float in, double roomSize, double damping) {
        double fb = Math.max(0.0, Math.min(0.999, roomSize));
        double damp = Math.max(0.0, Math.min(1.0, damping));

        double combsOut = 0.0;
        for (int i = 0; i < combCount; i++) {
            double[] buf = comb[i];
            int len = buf.length;
            double delayed = buf[combPos[i]];
            // One-pole low-pass inside the comb feedback path.
            double filtered = delayed * (1.0 - damp) + combFilter[i] * damp;
            combFilter[i] = filtered;
            buf[combPos[i]] = in + filtered * fb;
            combPos[i]++;
            if (combPos[i] == len) {
                combPos[i] = 0;
            }
            combsOut += filtered;
        }
        combsOut *= 0.25;

        double out = combsOut;
        for (int i = 0; i < allpassCount; i++) {
            double[] buf = allpass[i];
            int len = buf.length;
            double delayed = buf[allpassPos[i]];
            double y = delayed - ALLPASS_GAIN * out;
            buf[allpassPos[i]] = out + ALLPASS_GAIN * y;
            allpassPos[i]++;
            if (allpassPos[i] == len) {
                allpassPos[i] = 0;
            }
            out = y;
        }
        return (float) out;
    }

    public void reset() {
        for (double[] buf : comb) {
            Arrays.fill(buf, 0.0);
        }
        for (double[] buf : allpass) {
            Arrays.fill(buf, 0.0);
        }
        Arrays.fill(combPos, 0);
        Arrays.fill(allpassPos, 0);
        Arrays.fill(combFilter, 0.0);
    }
}
