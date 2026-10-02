package com.github.huchangjun118.spatialaudiomixerjava.service.dto;

/**
 * Request to add or update a listener.
 */
public record ListenerRequest(
        String id,
        Vector3Dto position,
        Vector3Dto forward,
        Vector3Dto up,
        Float gain) {
}
