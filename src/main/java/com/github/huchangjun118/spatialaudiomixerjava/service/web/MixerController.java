package com.github.huchangjun118.spatialaudiomixerjava.service.web;

import com.github.huchangjun118.spatialaudiomixerjava.model.ChannelLayout;
import com.github.huchangjun118.spatialaudiomixerjava.model.DegradationMode;
import com.github.huchangjun118.spatialaudiomixerjava.model.DegradationPolicy;
import com.github.huchangjun118.spatialaudiomixerjava.model.DopplerParams;
import com.github.huchangjun118.spatialaudiomixerjava.model.ListenerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.MixerConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.ReverbParams;
import com.github.huchangjun118.spatialaudiomixerjava.model.RenderResult;
import com.github.huchangjun118.spatialaudiomixerjava.model.SourceConfig;
import com.github.huchangjun118.spatialaudiomixerjava.model.Vec3;
import com.github.huchangjun118.spatialaudiomixerjava.service.MixerService;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.ConfigDto;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.EffectsDto;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.InputDto;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.ListenerDto;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.RenderResponse;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.SourceDto;
import com.github.huchangjun118.spatialaudiomixerjava.service.web.dto.Vec3Dto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/** REST control plane for the spatial audio mixer. */
@RestController
@RequestMapping("/api/mixer")
public class MixerController {

    private final MixerService service;

    public MixerController(MixerService service) {
        this.service = service;
    }

    @PostMapping("/config")
    public String configure(@RequestBody ConfigDto dto) {
        MixerConfig base = service.getMixer().getConfig();
        DegradationPolicy basePol = base.degradationPolicy();
        DegradationMode mode = dto.degradationMode() == null ? basePol.mode()
                : DegradationMode.valueOf(dto.degradationMode());
        DegradationPolicy policy = new DegradationPolicy(
                mode,
                or(dto.downsampleFactor(), basePol.downsampleFactor()),
                or(dto.maxActiveSources(), basePol.maxActiveSources()),
                or(dto.sourceCostUnits(), basePol.sourceCostUnits()),
                or(dto.capacityUnits(), basePol.capacityUnits()));
        DopplerParams doppler = base.doppler();
        ReverbParams reverb = base.reverb();
        if (dto.effects() != null) {
            EffectsDto e = dto.effects();
            doppler = new DopplerParams(
                    or(e.dopplerEnabled(), doppler.enabled()),
                    doppler.speedOfSound(), doppler.maxShiftRatio());
            reverb = new ReverbParams(
                    or(e.reverbEnabled(), reverb.enabled()),
                    reverb.roomSize(), reverb.damping(), reverb.wetGain());
        }
        ChannelLayout layout = dto.layout() == null ? base.layout()
                : ChannelLayout.valueOf(dto.layout());
        MixerConfig cfg = new MixerConfig(
                or(dto.sampleRate(), base.sampleRate()),
                or(dto.blockSize(), base.blockSize()),
                layout,
                or(dto.maxAudibleDistance(), base.maxAudibleDistance()),
                or(dto.referenceDistance(), base.referenceDistance()),
                or(dto.rolloffFactor(), base.rolloffFactor()),
                or(dto.smoothingRate(), base.smoothingRate()),
                policy, doppler, reverb);
        service.configure(cfg);
        return "ok";
    }

    @PostMapping("/sources")
    public String addSource(@RequestBody SourceDto dto) {
        service.addSource(toSource(dto, defaults()));
        return "ok";
    }

    @PostMapping("/listeners")
    public String addListener(@RequestBody ListenerDto dto) {
        service.addListener(toListener(dto, 1.0));
        return "ok";
    }

    @DeleteMapping("/sources/{id}")
    public String removeSource(@PathVariable String id) {
        service.removeSource(id);
        return "ok";
    }

    @DeleteMapping("/listeners/{id}")
    public String removeListener(@PathVariable String id) {
        service.removeListener(id);
        return "ok";
    }

    @PostMapping("/input")
    public int input(@RequestBody InputDto dto) {
        float[] data = new float[dto.samples() == null ? 0 : dto.samples().size()];
        for (int i = 0; i < data.length; i++) {
            data[i] = dto.samples().get(i);
        }
        return service.enqueueInput(dto.sourceId(), data, 0, data.length);
    }

    @PostMapping("/render")
    public List<RenderResponse> render(@RequestParam(defaultValue = "0") int samples) {
        int n = samples > 0 ? samples : service.getMixer().getConfig().blockSize();
        List<RenderResponse> out = new ArrayList<>();
        for (RenderResult r : service.renderAll(n)) {
            out.add(new RenderResponse(r.listenerId(), r.samples(), r.degraded(),
                    r.degradationMode() == null ? null : r.degradationMode().name(),
                    r.error(), Arrays.asList(r.output())));
        }
        return out;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    // --------------------------------------------------------------- mapping

    private static final SourceConfig DEFAULTS = new SourceConfig(
            "defaults", Vec3.ZERO, Vec3.ZERO, 1.0, 0.0, 1.0);

    private static SourceConfig defaults() {
        return DEFAULTS;
    }

    private static SourceConfig toSource(SourceDto dto, SourceConfig d) {
        Vec3 p = dto.position() == null ? d.position() : toVec(dto.position());
        Vec3 v = dto.velocity() == null ? d.velocity() : toVec(dto.velocity());
        return new SourceConfig(dto.id(), p, v,
                or(dto.gain(), d.gain()), or(dto.reverbSend(), d.reverbSend()),
                or(dto.dopplerFactor(), d.dopplerFactor()));
    }

    private static ListenerConfig toListener(ListenerDto dto, double defaultGain) {
        Vec3 p = dto.position() == null ? Vec3.ZERO : toVec(dto.position());
        Vec3 v = dto.velocity() == null ? Vec3.ZERO : toVec(dto.velocity());
        return new ListenerConfig(dto.id(), p, v, or(dto.gain(), defaultGain));
    }

    private static Vec3 toVec(Vec3Dto v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static int or(Integer v, int d) {
        return v == null ? d : v;
    }

    private static double or(Double v, double d) {
        return v == null ? d : v;
    }

    private static boolean or(Boolean v, boolean d) {
        return v == null ? d : v;
    }

    @ExceptionHandler({NoSuchElementException.class, IllegalArgumentException.class,
            IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(RuntimeException e) {
        return e.getMessage();
    }
}
