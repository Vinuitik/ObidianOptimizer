package com.obsidian.obsidian.hostfs;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HostFsController is a thin proxy with no injectable HTTP client seam, so these tests
 * stand up a REAL local HTTP server to play the host-wrapper's role — exercising the
 * actual java.net.http request building, status/body passthrough, and the 503 fallback,
 * rather than asserting on a mocked client that would just prove the mock was called.
 */
class HostFsControllerTest {

    HttpServer wrapper;
    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        HostFsController controller = new HostFsController();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        // wrapperUrl is set per-test once the fake wrapper's ephemeral port is known.
        this.controllerRef = controller;
    }

    HostFsController controllerRef;

    private void startWrapper(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        wrapper = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wrapper.createContext("/", handler);
        wrapper.start();
        ReflectionTestUtils.setField(controllerRef, "wrapperUrl", "http://127.0.0.1:" + wrapper.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (wrapper != null) wrapper.stop(0);
    }

    @Test
    void listDir_proxiesWrapperResponseBodyAndStatusVerbatim() throws Exception {
        startWrapper(ex -> {
            byte[] body = "{\"path\":\"/home\",\"dirs\":[{\"path\":\"/home/x\",\"name\":\"x\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        mvc.perform(get("/host/fs").param("path", "/home"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"path\":\"/home\",\"dirs\":[{\"path\":\"/home/x\",\"name\":\"x\"}]}"));
    }

    @Test
    void listDir_encodesPathParamIntoWrapperRequest() throws Exception {
        String[] seenQuery = new String[1];
        startWrapper(ex -> {
            seenQuery[0] = ex.getRequestURI().getRawQuery();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        mvc.perform(get("/host/fs").param("path", "C:/My Docs"))
            .andExpect(status().isOk());

        // Space must be percent/plus-encoded, not sent raw (would break the wrapper's parse).
        org.assertj.core.api.Assertions.assertThat(seenQuery[0]).contains("path=").doesNotContain(" ");
    }

    @Test
    void listDir_blankPath_omitsQueryStringEntirely() throws Exception {
        String[] seenQuery = new String[1];
        startWrapper(ex -> {
            seenQuery[0] = ex.getRequestURI().getRawQuery();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        mvc.perform(get("/host/fs")).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(seenQuery[0]).isNull();
    }

    @Test
    void wrapperUnreachable_returns503WithErrorBody_insteadOfPropagatingException() throws Exception {
        // No wrapper started at all — wrapperUrl points at a closed port.
        ReflectionTestUtils.setField(controllerRef, "wrapperUrl", "http://127.0.0.1:1");

        mvc.perform(get("/host/fs"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"error\":\"host helper unreachable\"}"));
    }

    @Test
    void getVault_propagatesNon200StatusFromWrapper() throws Exception {
        startWrapper(ex -> {
            byte[] body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        mvc.perform(get("/host/vault"))
            .andExpect(status().isNotFound())
            .andExpect(content().json("{\"error\":\"not found\"}"));
    }
}
