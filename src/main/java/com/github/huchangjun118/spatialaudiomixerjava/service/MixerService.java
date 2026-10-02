package com.github.huchangjun118.spatialaudiomixerjava.service;

import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioFormat;
import com.github.huchangjun118.spatialaudiomixerjava.audio.AudioSource;
import com.github.huchangjun118.spatialaudiomixerjava.audio.BufferAudioSource;
import com.github.huchangjun118.spatialaudiomixerjava.audio.ChannelLayout;
import com.github.huchangjun118.spatialaudiomixerjava.audio.WavWriter;
import com.github.huchangjun118.spatialaudiomixerjava.config.DegradationConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.DopplerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.ReverbConfig;
import com.github.huchangjun118.spatialaudiomixerjava.config.SourceConfig;
import com.github.huchangjun118.spatialaudiomixerjava.engine.DegradationLevel;
import com.github.huchangjun118.spatialaudiomixerjava.engine.SpatialAudioMixer;
import com.github.huchangjun118.spatialaudiomixerjava.service.dto.ListenerRequest;
import com.github.huchangjun118.spatialaudiomixerjava.service.dto.SourceRequest;
import com.github.huchangjun118.spatialaudiomixerjava.service.dto.StatusResponse;
import com.github.huchangjun118.spatialaudiomixerjava.service.dto.Vector3Dto;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring service wrapping the {@link SpatialAudioMixer}. Provides the
 * management API used by the REST controller.
 */
@Service
public class MixerService {

    private static final int SAMPLE_RATE = 44100;
    private static final int BLOCK_SIZE = 256;

    private SpatialAudioMixer mixer;
    private final AtomicLong sourceCounter = new AtomicLong();
    private final AtomicLong listenerCounter = new AtomicLong();

    @PostConstruct
    public void init() {
        MixerConfig config = MixerConfig.builder()
                .format(new AudioFormat(SAMPLE_RATE, BLOCK_SIZE, ChannelLayout.STEREO))
                .doppler(DopplerConfig.defaultEnabled())
                .reverb(ReverbConfig.defaultEnabled())
                .degradation(DegradationConfig.defaultConfig())
                .build();
        this.mixer = new SpatialAudioMixer(config);
    }

    public SpatialAudioMixer mixer() {
        return mixer;
    }

    public synchronized String addSource(SourceRequest req) {
        String id = req.id() != null && !req.id().isBlank()
                ? req.id() : "src-" + sourceCounter.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        AudioSource source = buildSource(req);
        SourceConfig config = buildSourceConfig(req);
        mixer.addSource(id, source, config);
        return id;
    }

    public synchronized void updateSource(String id, SourceRequest req) {
        SourceConfig config = buildSourceConfig(req);
        mixer.updateSource(id, config);
    }

    public synchronized void removeSource(String id) {
        mixer.removeSource(id);
    }

    public synchronized String addListener(ListenerRequest req) {
        String id = req.id() != null && !req.id().isBlank()
                ? req.id() : "lis-" + listenerCounter.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        ListenerConfig config = buildListenerConfig(req);
        mixer.addListener(id, config);
        return id;
    }

    public synchronized void updateListener(String id, ListenerRequest req) {
        ListenerConfig config = buildListenerConfig(req);
        mixer.updateListener(id, config);
    }

    public synchronized void removeListener(String id) {
        mixer.removeListener(id);
    }

    public synchronized void reset() {
        mixer.reset();
    }

    public void setDopplerEnabled(boolean enabled) {
        mixer.setDopplerEnabled(enabled);
    }

    public void setReverbEnabled(boolean enabled) {
        mixer.setReverbEnabled(enabled);
    }

    /**
     * Renders {@code blocks} blocks for the listener and returns WAV bytes.
     */
    public byte[] renderWav(String listenerId, int blocks) {
        int blockSize = mixer.config().format().blockSize();
        int channels = mixer.config().format().channelCount();
        float[][] all = new float[channels][blocks * blockSize];
        for (int b = 0; b < blocks; b++) {
            float[][] block = mixer.renderBlock(listenerId);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(block[ch], 0, all[ch], b * blockSize, blockSize);
            }
        }
        return WavWriter.write(all, mixer.config().format().sampleRate());
    }

    public StatusResponse status() {
        var snap = mixer.snapshot();
        DegradationLevel level = mixer.degradationLevel();
        return new StatusResponse(
                mixer.config().format().sampleRate(),
                mixer.config().format().blockSize(),
                mixer.config().format().channelLayout().name(),
                snap.sources().size(),
                snap.listeners().size(),
                level.code(),
                level.description(),
                mixer.renderBlockCount(),
                mixer.listenerFailureCount(),
                snap.mixerConfig().doppler().enabled(),
                snap.mixerConfig().reverb().enabled());
    }

    // --- Helpers -------------------------------------------------------------

    private SourceConfig buildSourceConfig(SourceRequest req) {
        Vector3Dto pos = req.position() != null ? req.position() : new Vector3Dto(0, 0, 0);
        return SourceConfig.builder()
                .position(pos.toVector3f())
                .gain(req.gain() != null ? req.gain() : 1.0f)
                .refDistance(req.refDistance() != null ? req.refDistance() : 1.0f)
                .maxDistance(req.maxDistance() != null ? req.maxDistance() : 100.0f)
                .rolloff(req.rolloff() != null ? req.rolloff() : 1.0f)
                .reverbSend(req.reverbSend() != null ? req.reverbSend() : 0.2f)
                .build();
    }

    private ListenerConfig buildListenerConfig(ListenerRequest req) {
        Vector3Dto pos = req.position() != null ? req.position() : new Vector3Dto(0, 0, 0);
        Vector3Dto fwd = req.forward() != null ? req.forward() : new Vector3Dto(0, 0, 1);
        Vector3Dto up = req.up() != null ? req.up() : new Vector3Dto(0, 1, 0);
        return ListenerConfig.builder()
                .position(pos.toVector3f())
                .forward(fwd.toVector3f())
                .up(up.toVector3f())
                .gain(req.gain() != null ? req.gain() : 1.0f)
                .build();
    }

    private AudioSource buildSource(SourceRequest req) {
        String waveform = req.waveform() != null ? req.waveform().toLowerCase() : "sine";
        double freq = req.frequency() != null ? req.frequency() : 440.0;
        double dur = req.durationSeconds() != null ? req.durationSeconds() : 1.0;
        boolean loop = req.loop() != null && req.loop();
        int n = (int) (SAMPLE_RATE * dur);
        if (n <= 0) n = SAMPLE_RATE;
        float[] samples = switch (waveform) {
            case "sine" -> sine(freq, n);
            case "square" -> square(freq, n);
            case "sawtooth" -> sawtooth(freq, n);
            case "noise" -> noise(n);
            case "silence" -> new float[n];
            default -> throw new IllegalArgumentException("unknown waveform: " + waveform);
        };
        return new BufferAudioSource(samples, loop);
    }

    private float[] sine(double freq, int n) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE);
        return s;
    }

    private float[] square(double freq, int n) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double phase = (freq * i / SAMPLE_RATE) % 1.0;
            s[i] = phase < 0.5 ? 0.8f : -0.8f;
        }
        return s;
    }

    private float[] sawtooth(double freq, int n) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double phase = (freq * i / SAMPLE_RATE) % 1.0;
            s[i] = (float) (2.0 * phase - 1.0) * 0.8f;
        }
        return s;
    }

    /** Deterministic noise (LCG), no randomness. */
    private float[] noise(int n) {
        float[] s = new float[n];
        long seed = 0x12345678L;
        for (int i = 0; i < n; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            s[i] = ((seed >> 40) / (float) (1L << 23)) * 0.5f;
        }
        return s;
    }
}
