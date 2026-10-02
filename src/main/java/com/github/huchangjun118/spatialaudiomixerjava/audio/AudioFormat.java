package com.github.huchangjun118.spatialaudiomixerjava.audio;

/**
 * Immutable audio format description.
 *
 * @param sampleRate    samples per second (e.g. 44100, 48000)
 * @param blockSize     number of frames per rendered block
 * @param channelLayout output channel layout
 */
public record AudioFormat(int sampleRate, int blockSize, ChannelLayout channelLayout) {

    public AudioFormat {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        if (channelLayout == null) {
            throw new IllegalArgumentException("channelLayout must not be null");
        }
    }

    public int channelCount() {
        return channelLayout.channelCount();
    }

    /** Real-time duration of one block in seconds. */
    public double blockDurationSeconds() {
        return (double) blockSize / (double) sampleRate;
    }

    @Override
    public String toString() {
        return sampleRate + " Hz, " + blockSize + " frames/block, " + channelLayout;
    }
}
