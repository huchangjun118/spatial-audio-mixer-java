package com.github.huchangjun118.spatialaudiomixerjava.service.web.dto;

public record SourceDto(String id, Vec3Dto position, Vec3Dto velocity, Double gain,
                        Double reverbSend, Double dopplerFactor) {
}
