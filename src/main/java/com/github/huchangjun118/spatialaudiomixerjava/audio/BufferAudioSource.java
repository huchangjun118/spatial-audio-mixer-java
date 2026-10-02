package com.github.huchangjun118.spatialaudiomixerjava.audio;

/**
 * An {@link AudioSource} backed by an immutable float array. Instances are
 * immutable and safe for concurrent reads.
 */
public final class BufferAudioSource implements AudioSource {

    private final float[] samples;
    private final boolean loop;

    public BufferAudioSource(float[] samples, boolean loop) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        this.samples = samples.clone();
        this.loop = loop;
    }

    @Override
    public long frameCount() {
        return samples.length;
    }

    @Override
    public boolean isLoop() {
        return loop;
    }

    @Override
    public void read(long position, float[] dest, int destOffset, int count) {
        int len = samples.length;
        for (int i = 0; i < count; i++) {
            long p = position + i;
            if (loop) {
                long m = p % len;
                if (m < 0) {
                    m += len;
                }
                dest[destOffset + i] = samples[(int) m];
            } else if (p >= 0 && p < len) {
                dest[destOffset + i] = samples[(int) p];
            } else {
                dest[destOffset + i] = 0f;
            }
        }
    }
}
