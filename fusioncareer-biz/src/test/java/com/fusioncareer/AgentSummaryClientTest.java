package com.fusioncareer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.AgentSummaryClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSummaryClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private HttpServer server;
    private AgentSummaryClient client;

    @BeforeEach
    void createServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/chat/summarize", readExchange -> {
            assertThat(readExchange.getRequestHeaders().getFirst("X-Internal-Token"))
                    .isEqualTo("internal-test");
            body.set(mapper.readTree(readExchange.getRequestBody()));
            byte[] writeBody = "{\"summary\":\"目标是寻找媒体实习\",\"throughMessageId\":\"42\"}"
                    .getBytes(StandardCharsets.UTF_8);
            readExchange.getResponseHeaders().set("Content-Type", "application/json");
            readExchange.sendResponseHeaders(200, writeBody.length);
            readExchange.getResponseBody().write(writeBody);
            readExchange.close();
        });
        server.start();
        client = new AgentSummaryClient(mapper);
        ReflectionTestUtils.setField(client, "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "connectTimeout", 1000L);
        ReflectionTestUtils.setField(client, "internalToken", "internal-test");
        ReflectionTestUtils.invokeMethod(client, "createClient");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void summarize() throws Exception {
        AgentSummaryClient.SummaryResponse readResponse = client.summarize(
                        new AgentSummaryClient.SummaryRequest(
                                "旧摘要",
                                List.of(new AgentSummaryClient.SummaryMessage("user", "找实习")),
                                "42"))
                .get(3, TimeUnit.SECONDS);

        assertThat(readResponse.summary()).isEqualTo("目标是寻找媒体实习");
        assertThat(body.get().path("previousSummary").asText()).isEqualTo("旧摘要");
        assertThat(body.get().path("messages").size()).isEqualTo(1);
    }
}
