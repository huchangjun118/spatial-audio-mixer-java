package com.github.huchangjun118.spatialaudiomixerjava.engine;

/**
 * Bounded mono input ring buffer for one source, addressed by an absolute sample
 * clock starting at zero.
 *
 * <p>Writes are non-blocking: when the producer outruns the slowest render
 * consumer and the buffer is full, the oldest samples are overwritten and counted
 * as dropped samples. Readers reading a clock position that has either not been
 * written yet or has already been overwritten deterministically receive
 * {@code 0.0f} (silence), never stale data, so overload can never block or crash
 * rendering.</p>
 */
final class InputRing {

    private final float[] buf;
    private final int mask;
    private long writeClock;
    private int dropped;

    InputRing(int capacity) {
        if (capacity < 8) {
            capacity = 8;
        }
        int n = Integer.highestOneBit(capacity);
        if (n != capacity) {
            n <<= 1;
        }
        this.buf = new float[n];
        this.mask = n - 1;
    }

    int capacity() {
        return buf.length;
    }

    /** Append samples; overwritten (never-consumed) samples count as dropped. */
    void write(float[] src, int off, int len) {
        for (int i = 0; i < len; i++) {
            int idx = (int) (writeClock & mask);
            if (writeClock >= buf.length) {
                // Slot about to be reused before all readers necessarily consumed it:
                // deterministic overload accounting, no blocking.
                dropped++;
            }
            buf[idx] = src[off + i];
            writeClock++;
        }
    }

    /** Read the sample at an absolute clock position; zero outside available range. */
    float get(long clock) {
        if (clock < 0 || clock >= writeClock) {
            return 0.0f;
        }
        if (writeClock > buf.length && clock < writeClock - buf.length) {
            return 0.0f;
        }
        return buf[(int) (clock & mask)];
    }

    long minClock() {
        return writeClock > buf.length ? writeClock - buf.length : 0L;
    }

    long maxClock() {
        return writeClock;
    }

    int droppedSamples() {
        return dropped;
    }

    void resetDroppedCounter() {
        dropped = 0;
    }
}
