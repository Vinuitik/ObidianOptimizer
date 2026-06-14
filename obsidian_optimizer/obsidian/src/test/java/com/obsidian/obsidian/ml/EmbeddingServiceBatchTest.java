package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit test for the batched embed path. A tiny JDK HttpServer stands in for the
 * Python embedder: it echoes one vector per posted text, encoding the input index
 * in the vector's first element so we can prove order is preserved and each vector
 * lands on the right chunk. No Docker / Spring context needed.
 */
class EmbeddingServiceBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private EmbeddingService service;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int vectorsToReturn = -1; // -1 → return one per input text

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            requestCount.incrementAndGet();
            JsonNode req = MAPPER.readTree(exchange.getRequestBody());
            int n = req.path("texts").size();
            int emit = vectorsToReturn >= 0 ? vectorsToReturn : n;

            StringBuilder sb = new StringBuilder("{\"dim\":3,\"embeddings\":[");
            for (int i = 0; i < emit; i++) {
                if (i > 0) sb.append(',');
                sb.append("[").append(i).append(".0,0.0,0.0]"); // first element = input index
            }
            sb.append("]}");
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();

        service = new EmbeddingService(mock(NoteChunkRepository.class), mock(MarkdownPreprocessor.class));
        ReflectionTestUtils.setField(service, "embedderUrl",
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void embedBatch_sendsOneRequest_andPreservesOrder() {
        List<float[]> vectors = service.embedBatch(List.of("alpha", "beta", "gamma"));

        assertThat(requestCount.get()).isEqualTo(1);          // ONE round-trip for 3 texts
        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)[0]).isEqualTo(0.0f);        // order preserved...
        assertThat(vectors.get(1)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(2)[0]).isEqualTo(2.0f);
        assertThat(vectors.get(0)).hasSize(3);                // dim intact
    }

    @Test
    void embedBatch_empty_makesNoRequest() {
        List<float[]> vectors = service.embedBatch(List.of());
        assertThat(vectors).isEmpty();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void embedBatch_countMismatch_returnsNull_soCallerCanRetry() {
        vectorsToReturn = 2; // server returns 2 vectors for 3 texts → contract violation
        List<float[]> vectors = service.embedBatch(List.of("a", "b", "c"));
        assertThat(vectors).isNull();
    }

    @Test
    void embed_singleText_delegatesToBatch() {
        float[] vec = service.embed("solo");
        assertThat(vec).isNotNull();
        assertThat(vec[0]).isEqualTo(0.0f);
        assertThat(requestCount.get()).isEqualTo(1);
    }
}
