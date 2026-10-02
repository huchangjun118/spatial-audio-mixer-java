package com.github.huchangjun118.spatialaudiomixerjava.service.dto;

import com.github.huchangjun118.spatialaudiomixerjava.math.Vector3f;

public record Vector3Dto(float x, float y, float z) {

    public Vector3f toVector3f() {
        return new Vector3f(x, y, z);
    }

    public static Vector3Dto from(Vector3f v) {
        return new Vector3Dto(v.x, v.y, v.z);
    }
}
