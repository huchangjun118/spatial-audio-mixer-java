package com.github.huchangjun118.spatialaudiomixerjava.service.web.dto;

public record ConfigDto(Integer sampleRate, Integer blockSize, String layout,
                        Double maxAudibleDistance, Double referenceDistance,
                        Double rolloffFactor, Double smoothingRate,
                        String degradationMode, Integer downsampleFactor,
                        Integer maxActiveSources, Integer sourceCostUnits, Integer capacityUnits,
                        EffectsDto effects) {
}
