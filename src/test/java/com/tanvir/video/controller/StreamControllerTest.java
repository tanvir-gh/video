package com.tanvir.video.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.tanvir.video.model.StreamSession;
import com.tanvir.video.service.StreamProcessingService;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
@ActiveProfiles("test")
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StreamProcessingService processingService;

    @Test
    void startStream_returnsSessionId() throws Exception {
        var session = new StreamSession("abc123", "test.m3u8");
        when(processingService.createSession(anyString())).thenReturn(session);

        mockMvc.perform(post("/api/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"test.m3u8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc123"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void startStream_rejectsMissingUrl() throws Exception {
        mockMvc.perform(post("/api/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSession_returnsNotFoundForUnknown() throws Exception {
        when(processingService.getSession("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/streams/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopStream_returnsNotFoundForUnknown() throws Exception {
        when(processingService.getSession("nonexistent")).thenReturn(null);

        mockMvc.perform(delete("/api/streams/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
