package com.github.huchangjun118.spatialaudiomixerjava.service;

import com.github.huchangjun118.spatialaudiomixerjava.engine.DegradationLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the REST API.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MixerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MixerService service;

    @Test
    void fullWorkflow_addSourceAndListener_renderWav() throws Exception {
        // Add a source.
        MvcResult addResult = mockMvc.perform(post("/api/mixer/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":{"x":0,"y":0,"z":10},"gain":1.0,
                                 "waveform":"sine","frequency":440,"durationSeconds":1.0,"loop":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String sourceId = addResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertNotNull(sourceId);

        // Add a listener.
        MvcResult lisResult = mockMvc.perform(post("/api/mixer/listeners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":{"x":0,"y":0,"z":0},"forward":{"x":0,"y":0,"z":1},
                                 "up":{"x":0,"y":1,"z":0},"gain":1.0}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String listenerId = lisResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Render 4 blocks -> WAV.
        MvcResult renderResult = mockMvc.perform(post("/api/mixer/render/" + listenerId)
                        .param("blocks", "4"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("audio/wav")))
                .andReturn();
        byte[] wav = renderResult.getResponse().getContentAsByteArray();
        assertTrue(wav.length > 44, "WAV should contain audio data");
        // WAV header check.
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);

        // Status should reflect 1 source and 1 listener.
        mockMvc.perform(get("/api/mixer/status"))
                .andExpect(status().isOk())
                .andExpect(content().json("{'sourceCount':1,'listenerCount':1}"));

        // Update source position.
        mockMvc.perform(put("/api/mixer/sources/" + sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":{"x":10,"y":0,"z":5},"gain":1.0}
                                """))
                .andExpect(status().isOk());

        // Remove source.
        mockMvc.perform(delete("/api/mixer/sources/" + sourceId))
                .andExpect(status().isOk());

        // Reset.
        mockMvc.perform(post("/api/mixer/reset"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/mixer/status"))
                .andExpect(status().isOk())
                .andExpect(content().json("{'sourceCount':0,'listenerCount':0}"));
    }

    @Test
    void effectToggles_work() throws Exception {
        mockMvc.perform(post("/api/mixer/effects/doppler").param("enabled", "false"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/mixer/effects/reverb").param("enabled", "false"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/mixer/status"))
                .andExpect(status().isOk())
                .andExpect(content().json("{'dopplerEnabled':false,'reverbEnabled':false}"));
        mockMvc.perform(post("/api/mixer/effects/doppler").param("enabled", "true"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/mixer/effects/reverb").param("enabled", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void degradationLevel_isReported() throws Exception {
        var status = service.status();
        assertEquals(DegradationLevel.NORMAL.code(), status.degradationLevel());
    }
}
