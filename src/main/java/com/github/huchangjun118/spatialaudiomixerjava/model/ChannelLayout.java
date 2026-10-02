package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Output channel layouts. */
public enum ChannelLayout {
    MONO(1),
    STEREO(2);

    private final int channels;

    ChannelLayout(int channels) {
        this.channels = channels;
    }

    public int channels() {
        return channels;
    }
}
