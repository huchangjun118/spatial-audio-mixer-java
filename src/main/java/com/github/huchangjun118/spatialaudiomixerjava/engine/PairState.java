package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.dsp.DopplerResampler;
import com.github.huchangjun118.spatialaudiomixerjava.dsp.ReverbTank;
import com.github.huchangjun118.spatialaudiomixerjava.dsp.Smoother;

/**
 * Persistent per (listener, source) render state. Guarded by the owning
 * listener's lock, so no two threads ever touch one instance.
 */
final class PairState {

    SourceRuntime source;
    final DopplerResampler resampler;
    ReverbTank reverb;
    boolean reverbWasEnabled;
    final Smoother gainSmoother;
    final Smoother wetSmoother;
    final Smoother ratioSmoother;
    long pairStartClock;
    long inputBaseClock;
    long readClock;
    long lastDspClock = Long.MIN_VALUE;
    long ageSamples;
    boolean dying;
    boolean everAdvanced;
    float lastDry;
    float lastWet;

    PairState(int initialClock, double initialGain, boolean reverbNeeded, int sampleRate) {
        this.resampler = new DopplerResampler(0L);
        this.gainSmoother = new Smoother(initialGain);
        this.wetSmoother = new Smoother(0.0);
        this.ratioSmoother = new Smoother(1.0);
        this.readClock = initialClock;
        this.pairStartClock = initialClock;
        if (reverbNeeded) {
            this.reverb = new ReverbTank(sampleRate);
        }
    }
}
