package com.github.huchangjun118.spatialaudiomixerjava.audio;

/**
 * Output channel layouts. Speaker angles are in degrees, 0 = front,
 * positive = right, negative = left (horizontal plane, elevation ignored).
 * Channel order follows ITU/DTS conventions; LFE is non-directional.
 */
public enum ChannelLayout {

    MONO(1, new float[]{0f}, new String[]{"M"}, new boolean[]{false}),
    STEREO(2, new float[]{-30f, 30f}, new String[]{"FL", "FR"}, new boolean[]{false, false}),
    QUAD(4, new float[]{-45f, 45f, -135f, 135f},
            new String[]{"FL", "FR", "RL", "RR"}, new boolean[]{false, false, false, false}),
    SURROUND_5_1(6,
            new float[]{-30f, 30f, 0f, 0f, -110f, 110f},
            new String[]{"FL", "FR", "C", "LFE", "RL", "RR"},
            new boolean[]{false, false, false, true, false, false}),
    SURROUND_7_1(8,
            new float[]{-30f, 30f, 0f, 0f, -135f, 135f, -90f, 90f},
            new String[]{"FL", "FR", "C", "LFE", "RL", "RR", "SL", "SR"},
            new boolean[]{false, false, false, true, false, false, false, false});

    private final int channelCount;
    private final float[] speakerAngles;
    private final String[] channelNames;
    private final boolean[] lfe;

    ChannelLayout(int channelCount, float[] speakerAngles, String[] channelNames, boolean[] lfe) {
        this.channelCount = channelCount;
        this.speakerAngles = speakerAngles.clone();
        this.channelNames = channelNames.clone();
        this.lfe = lfe.clone();
    }

    public int channelCount() {
        return channelCount;
    }

    public float speakerAngle(int channel) {
        return speakerAngles[channel];
    }

    public String channelName(int channel) {
        return channelNames[channel];
    }

    public boolean isLfe(int channel) {
        return lfe[channel];
    }

    /** Speaker unit vectors in the horizontal plane (x = right, z = forward). */
    public float speakerX(int channel) {
        return (float) Math.sin(Math.toRadians(speakerAngles[channel]));
    }

    public float speakerZ(int channel) {
        return (float) Math.cos(Math.toRadians(speakerAngles[channel]));
    }
}
