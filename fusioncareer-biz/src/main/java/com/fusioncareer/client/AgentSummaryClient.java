package com.fusioncareer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Java 到 Python 的无 Tool 对话摘要客户端。 */
@Component
@RequiredArgsConstructor
public class AgentSummaryClient {

    private final ObjectMapper objectMapper;

    @Value("${python-service.base-url:http://127.0.0.1:8000}")
    private String baseUrl;

    @Value("${python-service.connect-timeout:5000}")
    private long connectTimeout;

    @Value("${internal-service.token:}")
    private String internalToken;

    private HttpClient client;

    @PostConstruct
    void createClient() {
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    public CompletableFuture<SummaryResponse> summarize(SummaryRequest readRequest) {
        byte[] writeBody;
        try {
            writeBody = objectMapper.writeValueAsBytes(readRequest);
        } catch (JsonProcessingException readError) {
            return CompletableFuture.failedFuture(readError);
        }
        String readBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest writeRequest = HttpRequest.newBuilder()
                .uri(URI.create(readBaseUrl + "/api/internal/chat/summarize"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", internalToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeBody))
                .build();
        return client.sendAsync(writeRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(readResponse -> {
                    if (readResponse.statusCode() != 200
                            || readResponse.body() == null
                            || readResponse.body().length() > 16_000) {
                        throw new IllegalStateException(
                                "Python summary HTTP " + readResponse.statusCode());
                    }
                    try {
                        return objectMapper.readValue(readResponse.body(), SummaryResponse.class);
                    } catch (JsonProcessingException readError) {
                        throw new IllegalStateException("Python summary response invalid", readError);
                    }
                });
    }

    public record SummaryRequest(
            String previousSummary,
            List<SummaryMessage> messages,
            String throughMessageId) {
    }

    public record SummaryMessage(String role, String content) {
    }

    public record SummaryResponse(String summary, String throughMessageId) {
    }
}
