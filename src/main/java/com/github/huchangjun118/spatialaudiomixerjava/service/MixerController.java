package com.github.huchangjun118.spatialaudiomixerjava.service;

import com.github.huchangjun118.spatialaudiomixerjava.service.dto.ListenerRequest;
import com.github.huchangjun118.spatialaudiomixerjava.service.dto.SourceRequest;
import com.github.huchangjun118.spatialaudiomixerjava.service.dto.StatusResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API for the spatial audio mixer.
 */
@RestController
@RequestMapping("/api/mixer")
public class MixerController {

    private final MixerService service;

    public MixerController(MixerService service) {
        this.service = service;
    }

    // --- Sources -------------------------------------------------------------

    @PostMapping("/sources")
    public ResponseEntity<Map<String, String>> addSource(@RequestBody SourceRequest req) {
        String id = service.addSource(req);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PutMapping("/sources/{id}")
    public ResponseEntity<Void> updateSource(@PathVariable String id, @RequestBody SourceRequest req) {
        service.updateSource(id, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> removeSource(@PathVariable String id) {
        service.removeSource(id);
        return ResponseEntity.ok().build();
    }

    // --- Listeners -----------------------------------------------------------

    @PostMapping("/listeners")
    public ResponseEntity<Map<String, String>> addListener(@RequestBody ListenerRequest req) {
        String id = service.addListener(req);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PutMapping("/listeners/{id}")
    public ResponseEntity<Void> updateListener(@PathVariable String id, @RequestBody ListenerRequest req) {
        service.updateListener(id, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/listeners/{id}")
    public ResponseEntity<Void> removeListener(@PathVariable String id) {
        service.removeListener(id);
        return ResponseEntity.ok().build();
    }

    // --- Rendering -----------------------------------------------------------

    @PostMapping(value = "/render/{listenerId}", produces = "audio/wav")
    public ResponseEntity<byte[]> render(
            @PathVariable String listenerId,
            @RequestParam(defaultValue = "1") int blocks) {
        if (blocks < 1) {
            return ResponseEntity.badRequest().build();
        }
        byte[] wav = service.renderWav(listenerId, blocks);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/wav"));
        headers.setContentLength(wav.length);
        return new ResponseEntity<>(wav, headers, HttpStatus.OK);
    }

    // --- Effect toggles ------------------------------------------------------

    @PostMapping("/effects/doppler")
    public ResponseEntity<Void> setDoppler(@RequestParam boolean enabled) {
        service.setDopplerEnabled(enabled);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/effects/reverb")
    public ResponseEntity<Void> setReverb(@RequestParam boolean enabled) {
        service.setReverbEnabled(enabled);
        return ResponseEntity.ok().build();
    }

    // --- Status --------------------------------------------------------------

    @GetMapping("/status")
    public StatusResponse status() {
        return service.status();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        service.reset();
        return ResponseEntity.ok().build();
    }
}
