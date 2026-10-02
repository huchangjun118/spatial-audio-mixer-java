package com.github.huchangjun118.spatialaudiomixerjava.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Minimal WAV (16-bit PCM) writer. Deterministic, no external dependencies.
 */
public final class WavWriter {

    private WavWriter() {
    }

    /**
     * Encodes interleaved float samples (channels x frames) as a 16-bit PCM
     * WAV byte array.
     */
    public static byte[] write(float[][] channels, int sampleRate) {
        int numChannels = channels.length;
        int numFrames = channels[0].length;
        int bitsPerSample = 16;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = numFrames * numChannels * bitsPerSample / 8;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize)) {
            // RIFF header
            out.write('R'); out.write('I'); out.write('F'); out.write('F');
            writeInt(out, 36 + dataSize);
            out.write('W'); out.write('A'); out.write('V'); out.write('E');
            // fmt chunk
            out.write('f'); out.write('m'); out.write('t'); out.write(' ');
            writeInt(out, 16);
            writeShort(out, (short) 1); // PCM
            writeShort(out, (short) numChannels);
            writeInt(out, sampleRate);
            writeInt(out, byteRate);
            writeShort(out, (short) blockAlign);
            writeShort(out, (short) bitsPerSample);
            // data chunk
            out.write('d'); out.write('a'); out.write('t'); out.write('a');
            writeInt(out, dataSize);
            for (int f = 0; f < numFrames; f++) {
                for (int ch = 0; ch < numChannels; ch++) {
                    float s = channels[ch][f];
                    if (s > 1f) s = 1f;
                    if (s < -1f) s = -1f;
                    short pcm = (short) (s * 32767);
                    writeShort(out, pcm);
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write WAV", e);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}
