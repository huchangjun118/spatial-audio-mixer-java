package com.github.huchangjun118.spatialaudiomixerjava.service.dto;

public record StatusResponse(
        int sampleRate,
        int blockSize,
        String channelLayout,
        int sourceCount,
        int listenerCount,
        String degradationLevel,
        String degradationDescription,
        long renderBlockCount,
        long listenerFailureCount,
        boolean dopplerEnabled,
        boolean reverbEnabled) {
}
