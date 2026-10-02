package com.github.huchangjun118.spatialaudiomixerjava.service.web.dto;

import java.util.List;

public record InputDto(String sourceId, List<Float> samples) {
}
