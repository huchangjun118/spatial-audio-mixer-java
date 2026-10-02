package com.github.huchangjun118.spatialaudiomixerjava.service.web.dto;

public record ListenerDto(String id, Vec3Dto position, Vec3Dto velocity, Double gain) {
}
