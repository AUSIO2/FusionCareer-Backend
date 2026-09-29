package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.AgentStreamClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamClientTest {

    private HttpServer closeServer;
    private AgentStreamClient streamClient;

    @BeforeEach
    void createServer() throws Exception {
        closeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        closeServer.createContext("/api/internal/chat/stream", readExchange -> {
            assertThat(readExchange.getRequestHeaders().getFirst("X-Internal-Token"))
                    .isEqualTo("internal-test");
            assertThat(readExchange.getRequestHeaders().getFirst("X-Agent-Context"))
                    .isEqualTo("agent-context-test");
            assertThat(new String(readExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("request-1");
            byte[] writeBody = ("""
                    event: start
                    data: {"runId":"run-1"}

                    event: delta
                    data: {"seq":1,"text":"你好"}

                    event: done
                    data: {"content":"你好","finishReason":"stop","usage":{}}

                    """).getBytes(StandardCharsets.UTF_8);
            readExchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            readExchange.sendResponseHeaders(200, writeBody.length);
            readExchange.getResponseBody().write(writeBody);
            readExchange.close();
        });
        closeServer.start();

        streamClient = new AgentStreamClient(new ObjectMapper());
        ReflectionTestUtils.setField(streamClient, "baseUrl",
                "http://127.0.0.1:" + closeServer.getAddress().getPort());
        ReflectionTestUtils.setField(streamClient, "connectTimeout", 1000L);
        ReflectionTestUtils.setField(streamClient, "internalToken", "internal-test");
        ReflectionTestUtils.invokeMethod(streamClient, "createClient");
    }

    @AfterEach
    void stopServer() {
        closeServer.stop(0);
    }

    @Test
    void parseEvents() throws Exception {
        List<AgentStreamClient.StreamEvent> readEvents = new ArrayList<>();
        AgentStreamClient.StreamRequest createRequest = new AgentStreamClient.StreamRequest(
                "6274f9c9-bf88-42ea-b811-cdf37ef73a6f",
                1L,
                "request-1",
                "1001",
                "1002",
                "你好",
                List.of(),
                Map.of(),
                "",
                List.of());

        streamClient.streamChat(createRequest, "agent-context-test", readEvents::add)
                .get(3, TimeUnit.SECONDS);

        assertThat(readEvents).extracting(AgentStreamClient.StreamEvent::name)
                .containsExactly("start", "delta", "done");
        assertThat(readEvents.get(1).data().path("text").asText()).isEqualTo("你好");
    }
}
