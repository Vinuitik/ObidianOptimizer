package com.obsidian.obsidian.vaults;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultWrapperClientTest {

    @Test
    void register_postsIdAndHostPathJson_andReturnsStatus() throws Exception {
        AtomicReference<String> got = new AtomicReference<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/vaults", ex -> {
            got.set(ex.getRequestMethod() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(202, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        try {
            VaultWrapperClient c = new VaultWrapperClient();
            ReflectionTestUtils.setField(c, "wrapperUrl", "http://127.0.0.1:" + srv.getAddress().getPort());
            VaultWrapperClient.Result r = c.register(7, "/h/My \"Vault\"");
            assertThat(r.status()).isEqualTo(202);
            assertThat(got.get()).isEqualTo("POST {\"id\":7,\"host_path\":\"/h/My \\\"Vault\\\"\"}");
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void register_unreachableWrapper_is503() {
        VaultWrapperClient c = new VaultWrapperClient();
        ReflectionTestUtils.setField(c, "wrapperUrl", "http://127.0.0.1:1");
        assertThatThrownBy(() -> c.register(2, "/h")).isInstanceOf(VaultException.class)
            .extracting(e -> ((VaultException) e).status()).isEqualTo(503);
    }
}
