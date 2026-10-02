package com.github.huchangjun118.spatialaudiomixerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MixerControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + "/api/mixer" + path;
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        ObjectMapper om = new ObjectMapper();

        assertEquals("ok", rest.getForObject(url("/health"), String.class));

        // Configure a small stereo mixer, reverb on, doppler off.
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("sampleRate", 48000);
        cfg.put("blockSize", 256);
        cfg.put("layout", "STEREO");
        cfg.put("maxAudibleDistance", 100.0);
        cfg.put("referenceDistance", 1.0);
        cfg.put("rolloffFactor", 1.0);
        cfg.put("smoothingRate", 64.0);
        cfg.put("degradationMode", "LIMIT_SOURCES");
        cfg.put("downsampleFactor", 2);
        cfg.put("maxActiveSources", 64);
        cfg.put("sourceCostUnits", 1);
        cfg.put("capacityUnits", 100000);
        cfg.put("effects", Map.of("dopplerEnabled", false, "reverbEnabled", true));
        assertEquals("ok", rest.postForObject(url("/config"), cfg, String.class));

        Map<String, Object> listener = Map.of("id", "L1",
                "position", Map.of("x", 0, "y", 0, "z", 0),
                "velocity", Map.of("x", 0, "y", 0, "z", 0), "gain", 1.0);
        assertEquals("ok", rest.postForObject(url("/listeners"), listener, String.class));

        float[] samples = new float[512];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.1f * (float) Math.sin(2 * Math.PI * 220 * i / 48000.0);
        }
        Float[] boxed = new Float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            boxed[i] = samples[i];
        }
        Map<String, Object> source = Map.of("id", "S1",
                "position", Map.of("x", 3, "y", 0, "z", 8),
                "velocity", Map.of("x", 0, "y", 0, "z", 0),
                "gain", 0.8, "reverbSend", 0.5, "dopplerFactor", 1.0);
        assertEquals("ok", rest.postForObject(url("/sources"), source, String.class));
        Map<String, Object> input = Map.of("sourceId", "S1", "samples", List.of(boxed));
        Integer dropped = rest.postForObject(url("/input"), input, Integer.class);
        assertEquals(0, dropped);

        ResponseEntity<String> resp = rest.postForEntity(
                url("/render?samples=256"), null, String.class);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode arr = om.readTree(resp.getBody());
        assertEquals(1, arr.size());
        JsonNode first = arr.get(0);
        assertEquals("L1", first.get("listenerId").asText());
        assertEquals(256, first.get("samples").asInt());
        assertEquals(2, first.get("channels").size());
        assertTrue(first.get("channels").get(0).size() > 0);

        assertEquals("ok", rest.exchange(url("/sources/S1"), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class).getBody());
        assertEquals("ok", rest.exchange(url("/listeners/L1"), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class).getBody());
    }

    @Test
    void unknownSourceReturnsBadRequest() {
        Map<String, Object> input = Map.of("sourceId", "ghost",
                "samples", List.of(0.1f));
        org.springframework.http.ResponseEntity<String> r = rest.postForEntity(
                url("/input"), input, String.class);
        assertEquals(400, r.getStatusCode().value());
    }
}
