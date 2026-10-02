package com.github.huchangjun118.spatialaudiomixerjava.model;

/** Result of rendering one block for one listener. */
public record RenderResult(String listenerId, float[][] output, int samples,
                           boolean degraded, DegradationMode degradationMode, String error) {
}
