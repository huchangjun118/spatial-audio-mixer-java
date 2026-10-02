package com.github.huchangjun118.spatialaudiomixerjava.engine;

import com.github.huchangjun118.spatialaudiomixerjava.audio.ChannelLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 2D vector-base amplitude panning (VBAP) on the horizontal plane.
 *
 * <p>Given a source azimuth (0 = front, positive = right) and the channel
 * layout, gains are computed for the adjacent speaker pair bracketing the
 * source direction and normalized to constant power. The LFE channel is
 * non-directional and is excluded from panning.
 */
final class Panning {

    private Panning() {
    }

    private static final class Speaker {
        final int channel;
        final double angleRad;
        final double sx;
        final double sz;

        Speaker(int channel, double angleDeg) {
            this.channel = channel;
            this.angleRad = Math.toRadians(angleDeg);
            this.sx = Math.sin(angleRad);
            this.sz = Math.cos(angleRad);
        }
    }

    /**
     * Computes per-channel panning gains.
     *
     * @param layout  channel layout
     * @param azimuth source azimuth in radians (0 = front, positive = right)
     * @param out     gains array (length = channel count), filled in
     */
    static void computeGains(ChannelLayout layout, double azimuth, float[] out) {
        int n = layout.channelCount();
        java.util.Arrays.fill(out, 0f);

        if (n == 1) {
            out[0] = 1f;
            return;
        }

        List<Speaker> speakers = new ArrayList<>();
        for (int ch = 0; ch < n; ch++) {
            if (!layout.isLfe(ch)) {
                speakers.add(new Speaker(ch, layout.speakerAngle(ch)));
            }
        }
        speakers.sort(Comparator.comparingDouble(s -> s.angleRad));

        double theta = azimuth;
        // Find the segment [s0, s1] (in circular order) containing theta.
        Speaker s0 = speakers.get(speakers.size() - 1);
        Speaker s1 = speakers.get(0);
        for (int i = 0; i < speakers.size(); i++) {
            Speaker cur = speakers.get(i);
            Speaker next = speakers.get((i + 1) % speakers.size());
            double a0 = cur.angleRad;
            double a1 = next.angleRad;
            if (i == speakers.size() - 1) {
                // wrap-around segment: a0 .. a1 + 2pi
                if (theta >= a0 || theta <= a1) {
                    s0 = cur;
                    s1 = next;
                    break;
                }
            } else if (theta >= a0 && theta <= a1) {
                s0 = cur;
                s1 = next;
                break;
            }
        }

        double sinT = Math.sin(theta);
        double cosT = Math.cos(theta);
        double det = s0.sx * s1.sz - s1.sx * s0.sz;

        double g0;
        double g1;
        if (Math.abs(det) < 1e-9) {
            // Coincident speakers (e.g. center + LFE): nearest speaker only.
            double d0 = angleDiff(theta, s0.angleRad);
            double d1 = angleDiff(theta, s1.angleRad);
            g0 = d0 <= d1 ? 1.0 : 0.0;
            g1 = 1.0 - g0;
        } else {
            g0 = (sinT * s1.sz - cosT * s1.sx) / det;
            g1 = (cosT * s0.sx - sinT * s0.sz) / det;
            if (g0 < 0) g0 = 0;
            if (g1 < 0) g1 = 0;
        }

        double sum = g0 * g0 + g1 * g1;
        if (sum < 1e-12) {
            // Source exactly between with zero gains: fall back to nearest.
            double d0 = angleDiff(theta, s0.angleRad);
            double d1 = angleDiff(theta, s1.angleRad);
            if (d0 <= d1) {
                out[s0.channel] = 1f;
            } else {
                out[s1.channel] = 1f;
            }
            return;
        }
        double norm = 1.0 / Math.sqrt(sum);
        out[s0.channel] = (float) (g0 * norm);
        out[s1.channel] = (float) (g1 * norm);
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b);
        while (d > Math.PI) d = Math.abs(d - 2 * Math.PI);
        return d;
    }
}
