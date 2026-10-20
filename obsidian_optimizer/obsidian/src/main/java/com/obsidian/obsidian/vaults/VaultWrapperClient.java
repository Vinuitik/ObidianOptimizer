package com.obsidian.obsidian.vaults;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the host-wrapper {@code POST /vaults {id, host_path}} (validate, regenerate
 * docker-compose.vaults.yml, detached {@code compose up -d}). Idempotent on the wrapper side.
 */
@Component
public class VaultWrapperClient {

    private static final Logger log = LoggerFactory.getLogger(VaultWrapperClient.class);

    // HTTP_1_1: the wrapper (Flask) can't do the JDK client's default h2c upgrade.
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${wrapper.url:http://host.docker.internal:5001}")
    private String wrapperUrl;

    /** Wrapper answer: HTTP status (202 ok, 400/409 rejected) + raw JSON body. */
    public record Result(int status, String body) {}

    /** @throws VaultException 503 if the wrapper is unreachable. */
    public Result register(long id, String hostPath) {
        String json = "{\"id\":" + id + ",\"host_path\":" + quote(hostPath) + "}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(wrapperUrl + "/vaults"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Result(res.statusCode(), res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException(503, "host helper unreachable");
        } catch (Exception e) {
            log.warn("[vaults] host-wrapper unreachable: {}", e.getMessage());
            throw new VaultException(503, "host helper unreachable");
        }
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
