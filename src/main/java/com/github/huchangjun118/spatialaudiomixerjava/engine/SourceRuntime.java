package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioSource;
import com.github.huchangjun118.spatialaudiomixerjava.audio.ChannelLayout;
import com.github.huchangjun118.spatialaudiomixerjava.config.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.SourceConfig;
import com.github.huchangjun118.spatialaudiomixerjava.math.Vector3f;

/**
 * Per-source, per-listener render state. Owns the playback position, the
 * Doppler delay line and the per-source reverb. State advances sample by
 * sample with the global frame counter so that block-by-block rendering is
 * identical to one-shot rendering.
 *
 * <p>All parameters (gains, panning, delay, reverb send) are smoothed
 * per-sample with fixed time constants, so source/listener movement never
 * causes clicks and block boundaries are inaudible.
 */
public final class SourceRuntime {

    private static final double GAIN_TAU = 0.010;   // 10 ms gain smoothing
    private static final double DELAY_TAU = 0.030;  // 30 ms delay smoothing

    private final String sourceId;
    private final AudioSource source;
    private final MixerConfig mixerConfig;
    private final int sampleRate;
    private final int blockSize;
    private final int channelCount;
    private final int maxDelaySamples;

    private SourceConfig config;

    // Playback position into the source audio.
    private long readPosition;

    // Doppler delay line (ring buffer).
    private float[] ring;
    private long ringWrite;
    private double ringRead;
    private double delay;       // smoothed delay in delay-line units
    private double prevDelay;

    // Per-source reverb (created lazily when reverb is first enabled).
    private Reverb reverb;

    // Smoothed parameters.
    private final float[] gains;
    private float reverbInGain;
    private final float[] lastOut;

    public SourceRuntime(String sourceId, AudioSource source, SourceConfig config,
                         MixerConfig mixerConfig, int maxDelaySamples) {
        this.sourceId = sourceId;
        this.source = source;
        this.config = config;
        this.mixerConfig = mixerConfig;
        this.sampleRate = mixerConfig.format().sampleRate();
        this.blockSize = mixerConfig.format().blockSize();
        this.channelCount = mixerConfig.format().channelCount();
        this.maxDelaySamples = maxDelaySamples;
        this.gains = new float[channelCount];
        this.lastOut = new float[channelCount];
    }

    public String sourceId() {
        return sourceId;
    }

    public void updateConfig(SourceConfig config) {
        this.config = config;
    }

    /**
     * Renders one frame into {@code out}.
     *
     * @param out          output buffer (channels x blockSize)
     * @param frame        global frame index (monotonic, shared across blocks)
     * @param halfRate     when true only even frames are processed
     * @param listenerPos  listener position
     * @param listenerFwd  listener forward vector (normalized)
     * @param listenerUp   listener up vector (normalized)
     * @param listenerGain listener master gain
     * @param reverbOn     whether reverb processing is active this frame
     * @param dopplerOn    whether Doppler processing is active this frame
     */
    public void renderFrame(float[][] out, long frame, boolean halfRate,
                            Vector3f listenerPos, Vector3f listenerFwd, Vector3f listenerUp,
                            float listenerGain, boolean reverbOn, boolean dopplerOn) {
        int frameInBlock = (int) (frame % blockSize);

        // In half-rate mode odd frames hold the last computed sample.
        if (halfRate && (frame & 1L) != 0L) {
            for (int ch = 0; ch < channelCount; ch++) {
                out[ch][frameInBlock] += lastOut[ch];
            }
            return;
        }

        double dt = halfRate ? 2.0 : 1.0;
        double gainCoef = 1.0 - Math.exp(-dt / (GAIN_TAU * sampleRate));
        double delayCoef = 1.0 - Math.exp(-dt / (DELAY_TAU * sampleRate));

        // --- Geometry -------------------------------------------------------
        Vector3f fwd = listenerFwd.normalize();
        Vector3f up = listenerUp.normalize();
        Vector3f right = up.cross(fwd).normalize();

        Vector3f toSource = config.position().sub(listenerPos);
        float dist = toSource.length();
        Vector3f dir = dist > 1e-9f ? toSource.mul(1.0f / dist) : Vector3f.FORWARD;

        double azimuth = Math.atan2(dir.dot(right), dir.dot(fwd));
        float att = distanceAttenuation(dist, config);

        // --- Target gains --------------------------------------------------
        float[] pan = new float[channelCount];
        Panning.computeGains(mixerConfig.format().channelLayout(), azimuth, pan);
        float dryGain = config.gain() * listenerGain * att;
        float[] targetGains = new float[channelCount];
        for (int ch = 0; ch < channelCount; ch++) {
            targetGains[ch] = pan[ch] * dryGain;
        }
        // LFE gets a mono-ish feed (non-directional).
        ChannelLayout layout = mixerConfig.format().channelLayout();
        for (int ch = 0; ch < channelCount; ch++) {
            if (layout.isLfe(ch)) {
                targetGains[ch] = 0.5f * dryGain;
            }
        }

        // --- Smooth gains ---------------------------------------------------
        for (int ch = 0; ch < channelCount; ch++) {
            gains[ch] += (float) ((targetGains[ch] - gains[ch]) * gainCoef);
        }
        float targetReverbInGain = config.gain() * att;
        reverbInGain += (float) ((targetReverbInGain - reverbInGain) * gainCoef);

        // --- Read source sample --------------------------------------------
        float input = readSourceSample();
        readPosition += (long) dt;

        // --- Doppler (variable delay line) ---------------------------------
        float dry;
        if (dopplerOn) {
            dry = processDoppler(input, dist, dt, delayCoef);
        } else {
            dry = input;
        }

        // --- Reverb send ----------------------------------------------------
        float wet = 0f;
        if (reverbOn && mixerConfig.reverb().enabled()) {
            if (reverb == null) {
                reverb = new Reverb(mixerConfig.reverb(), sampleRate);
            }
            float reverbIn = input * reverbInGain;
            wet = reverb.process(reverbIn) * config.reverbSend();
        }

        // --- Mix to output channels ----------------------------------------
        for (int ch = 0; ch < channelCount; ch++) {
            float sample = gains[ch] * dry + wet;
            lastOut[ch] = sample;
            out[ch][frameInBlock] += sample;
        }
    }

    private float readSourceSample() {
        float[] buf = new float[1];
        source.read(readPosition, buf, 0, 1);
        return buf[0];
    }

    private float processDoppler(float input, float dist, double dt, double delayCoef) {
        if (ring == null) {
            // Ring buffer is allocated lazily on first use.
            ring = new float[maxDelaySamples + 4];
            ringWrite = 0;
            ringRead = 0;
            delay = 0;
            prevDelay = 0;
        }

        // In half-rate mode the delay line is clocked at sr/2, so delays are
        // expressed in half-sample units.
        double targetDelayUnits = Math.min(
                dist / mixerConfig.doppler().speedOfSound() * sampleRate, maxDelaySamples) / dt;

        prevDelay = delay;
        delay += (targetDelayUnits - delay) * delayCoef;
        // Clamp the playback rate to a small positive value: a source cannot
        // move acoustically faster than the delay line can fill. This prevents
        // the read head from moving backward into unwritten ring data.
        double rate = Math.max(0.1, 1.0 - (delay - prevDelay));

        // Write one input sample into the ring per processed frame.
        int writeIdx = (int) Math.floorMod(ringWrite, ring.length);
        ring[writeIdx] = input;
        ringWrite++;

        // Read with the modulated rate.
        ringRead += rate;
        double maxDelayUnits = maxDelaySamples / dt;
        if (ringRead > ringWrite) {
            ringRead = ringWrite;
        }
        if (ringRead < ringWrite - maxDelayUnits) {
            ringRead = ringWrite - maxDelayUnits;
        }
        // Never read before the start of the written history.
        if (ringRead < 0) {
            ringRead = 0;
        }

        long idx = (long) Math.floor(ringRead);
        double frac = ringRead - idx;
        int i0 = (int) Math.floorMod(idx, ring.length);
        int i1 = (int) Math.floorMod(idx + 1, ring.length);
        float s0 = ring[i0];
        float s1 = ring[i1];
        return (float) (s0 + (s1 - s0) * frac);
    }

    /**
     * Distance attenuation: inverse-distance roll-off with a smooth fade to
     * zero at {@code maxDistance} (no hard cut at the boundary).
     */
    private static float distanceAttenuation(float dist, SourceConfig c) {
        if (dist <= c.refDistance()) {
            return 1.0f;
        }
        float att = c.refDistance() / (c.refDistance() + c.rolloff() * (dist - c.refDistance()));
        float fadeMargin = Math.max(1.0f, 0.05f * c.maxDistance());
        if (dist > c.maxDistance() - fadeMargin) {
            float t = (c.maxDistance() - dist) / fadeMargin;
            if (t < 0f) {
                t = 0f;
            }
            att *= t * t * (3f - 2f * t); // smoothstep
        }
        return att;
    }
}
