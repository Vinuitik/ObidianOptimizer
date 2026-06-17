package com.obsidian.obsidian.hostfs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin proxy to the host-wrapper's filesystem endpoints. The wrapper runs on the
 * host (outside Docker), so it — and only it — can browse the user's real drives
 * and switch which folder docker-compose mounts as the vault.
 *
 * Frontend hits /api/host/* → nginx strips /api/ → here → wrapper.url (/fs/list,
 * /vault-path). Switching the vault makes the wrapper rewrite .env and run
 * `docker compose up -d`, which recreates THIS container — so the PUT may not get
 * a clean response. The frontend handles that by polling and reloading.
 */
@RestController
public class HostFsController {

    private static final Logger log = LoggerFactory.getLogger(HostFsController.class);

    // HTTP_1_1: the wrapper (Flask/uvicorn family) can't do the JDK client's
    // default h2c upgrade — see StatsController for the same workaround.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    @Value("${wrapper.url:http://host.docker.internal:5001}")
    private String wrapperUrl;

    @GetMapping("host/fs")
    public ResponseEntity<String> listDir(@RequestParam(required = false) String path) {
        String q = (path == null || path.isBlank())
            ? ""
            : "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return proxy(HttpRequest.newBuilder()
            .uri(URI.create(wrapperUrl + "/fs/list" + q))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build(), "listDir");
    }

    @GetMapping("host/vault")
    public ResponseEntity<String> getVault() {
        return proxy(HttpRequest.newBuilder()
            .uri(URI.create(wrapperUrl + "/vault-path"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build(), "getVault");
    }

    @PutMapping("host/vault")
    public ResponseEntity<String> setVault(@RequestBody String body) {
        return proxy(HttpRequest.newBuilder()
            .uri(URI.create(wrapperUrl + "/vault-path"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(), "setVault");
    }

    private ResponseEntity<String> proxy(HttpRequest req, String tag) {
        try {
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(res.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return wrapperDown(tag);
        } catch (Exception e) {
            log.warn("[{}] host-wrapper unreachable: {}", tag, e.getMessage());
            return wrapperDown(tag);
        }
    }

    private ResponseEntity<String> wrapperDown(String tag) {
        return ResponseEntity.status(503)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"host helper unreachable\"}");
    }
}
