package com.github.huchangjun118.spatialaudiomixerjava.math;

/**
 * Immutable 3D vector. Coordinate system: x = right, y = up, z = forward
 * (left-handed, common for spatial audio).
 */
public final class Vector3f {

    public static final Vector3f ZERO = new Vector3f(0, 0, 0);
    public static final Vector3f FORWARD = new Vector3f(0, 0, 1);
    public static final Vector3f UP = new Vector3f(0, 1, 0);

    public final float x;
    public final float y;
    public final float z;

    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f add(Vector3f o) {
        return new Vector3f(x + o.x, y + o.y, z + o.z);
    }

    public Vector3f sub(Vector3f o) {
        return new Vector3f(x - o.x, y - o.y, z - o.z);
    }

    public Vector3f mul(float s) {
        return new Vector3f(x * s, y * s, z * s);
    }

    public float dot(Vector3f o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vector3f cross(Vector3f o) {
        return new Vector3f(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public float distanceTo(Vector3f o) {
        return sub(o).length();
    }

    public Vector3f normalize() {
        float len = length();
        if (len < 1e-12f) {
            return ZERO;
        }
        float inv = 1.0f / len;
        return new Vector3f(x * inv, y * inv, z * inv);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vector3f)) return false;
        Vector3f v = (Vector3f) o;
        return Float.compare(x, v.x) == 0
                && Float.compare(y, v.y) == 0
                && Float.compare(z, v.z) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.hashCode(x);
        h = 31 * h + Float.hashCode(y);
        h = 31 * h + Float.hashCode(z);
        return 31 * h + Float.hashCode(z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
