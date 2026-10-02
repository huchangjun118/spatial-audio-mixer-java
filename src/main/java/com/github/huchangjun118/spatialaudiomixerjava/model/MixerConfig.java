package com.github.huchangjun118.spatialaudiomixerjava.model;

/**
 * Immutable mixer-wide configuration.
 *
 * @param sampleRate          render sample rate in Hz (e.g. 48000)
 * @param blockSize           internal parameter-latch block size in samples
 * @param layout              output channel layout (MONO or STEREO)
 * @param maxAudibleDistance  distance at and beyond which a source is fully inaudible
 * @param referenceDistance   distance up to which distance gain is unity
 * @param rolloffFactor       inverse-distance exponent
 * @param smoothingRate       time constant in samples for all per-sample ramps
 * @param degradationPolicy   overload behaviour
 * @param doppler             global Doppler parameters (enabled flag and tuning)
 * @param reverb              global spatial reverb parameters
 */
public record MixerConfig(int sampleRate, int blockSize, ChannelLayout layout,
                          double maxAudibleDistance, double referenceDistance,
                          double rolloffFactor, double smoothingRate,
                          DegradationPolicy degradationPolicy,
                          DopplerParams doppler, ReverbParams reverb) {

    public static MixerConfig defaults() {
        return new MixerConfig(48000, 512, ChannelLayout.STEREO,
                100.0, 1.0, 1.0, 128.0,
                DegradationPolicy.unlimited(DegradationMode.LIMIT_SOURCES),
                new DopplerParams(false, 343.0, 0.1),
                new ReverbParams(false, 0.85, 0.3, 0.25));
    }
}
