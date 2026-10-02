package com.github.huchangjun118.spatialaudiomixerjava.audio;

/**
 * A stateless source of mono audio samples. Playback position is supplied by
 * the caller so that each listener can advance independently (listener
 * outputs never share playback state).
 *
 * <p>Implementations must be safe for concurrent reads from multiple
 * threads (the mixer may render several listeners concurrently).
 */
public interface AudioSource {

    /**
     * Total number of frames, or -1 if unknown/infinite.
     */
    long frameCount();

    /**
     * Reads {@code count} frames starting at {@code position} into
     * {@code dest} starting at {@code destOffset}. Frames past the end are
     * filled with silence (or wrapped when {@link #isLoop()} is true).
     */
    void read(long position, float[] dest, int destOffset, int count);

    /**
     * Whether playback wraps around when reaching the end.
     */
    boolean isLoop();
}
