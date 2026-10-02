package com.github.huchangjun118.spatialaudiomixerjava.service.web.dto;

import java.util.List;

public record RenderResponse(String listenerId, int samples, boolean degraded,
                             String degradationMode, String error, List<float[]> channels) {
}
