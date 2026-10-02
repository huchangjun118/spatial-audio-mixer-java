package com.github.huchangjun118.spatialaudiomixerjava.config;

import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioFormat;

/**
 * Immutable top-level mixer configuration.
 *
 * @param format       sample rate, block size and channel layout
 * @param doppler      Doppler effect configuration
 * @param reverb       reverb effect configuration
 * @param degradation overload degradation configuration
 */
public record MixerConfig(
        AudioFormat format,
        DopplerConfig doppler,
        ReverbConfig reverb,
        DegradationConfig degradation) {

    public MixerConfig {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        if (doppler == null) {
            throw new IllegalArgumentException("doppler must not be null");
        }
        if (reverb == null) {
            throw new IllegalArgumentException("reverb must not be null");
        }
        if (degradation == null) {
            throw new IllegalArgumentException("degradation must not be null");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private AudioFormat format = new AudioFormat(44100, 256,
                com.github.huchangjun118.spatialaudiomixerjava.audio.ChannelLayout.STEREO);
        private DopplerConfig doppler = DopplerConfig.defaultEnabled();
        private ReverbConfig reverb = ReverbConfig.defaultEnabled();
        private DegradationConfig degradation = DegradationConfig.defaultConfig();

        public Builder format(AudioFormat f) { this.format = f; return this; }
        public Builder doppler(DopplerConfig d) { this.doppler = d; return this; }
        public Builder reverb(ReverbConfig r) { this.reverb = r; return this; }
        public Builder degradation(DegradationConfig d) { this.degradation = d; return this; }

        public MixerConfig build() {
            return new MixerConfig(format, doppler, reverb, degradation);
        }
    }
}
