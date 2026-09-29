package com.fusioncareer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Java 到 Python 的专用 SSE 客户端。
 */
@Component
@RequiredArgsConstructor
public class AgentStreamClient {

    private final ObjectMapper objectMapper;

    @Value("${python-service.base-url:http://127.0.0.1:8000}")
    private String baseUrl;

    @Value("${python-service.connect-timeout:5000}")
    private long connectTimeout;

    @Value("${internal-service.token:}")
    private String internalToken;

    private HttpClient streamClient;

    @PostConstruct
    void createClient() {
        streamClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    public CompletableFuture<Void> streamChat(
            StreamRequest readRequest,
            String readAgentContext,
            Consumer<StreamEvent> consumeEvent) {
        byte[] writeBody;
        try {
            writeBody = objectMapper.writeValueAsBytes(readRequest);
        } catch (JsonProcessingException readError) {
            return CompletableFuture.failedFuture(readError);
        }
        String readBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest writeRequest = HttpRequest.newBuilder()
                .uri(URI.create(readBaseUrl + "/api/internal/chat/stream"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Internal-Token", internalToken)
                .header("X-Agent-Context", readAgentContext)
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeBody))
                .build();
        return streamClient.sendAsync(writeRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(readResponse -> readEvents(readResponse, consumeEvent));
    }

    private void readEvents(
            HttpResponse<java.io.InputStream> readResponse,
            Consumer<StreamEvent> consumeEvent) {
        if (readResponse.statusCode() != 200) {
            try (java.io.InputStream closeBody = readResponse.body()) {
                closeBody.readNBytes(512);
            } catch (IOException ignored) {
                // 状态码已经足以生成安全上游错误。
            }
            throw new IllegalStateException("Python chat HTTP " + readResponse.statusCode());
        }
        try (BufferedReader readLines = new BufferedReader(new InputStreamReader(
                readResponse.body(), StandardCharsets.UTF_8))) {
            String readEvent = "message";
            StringBuilder readData = new StringBuilder();
            String readLine;
            while ((readLine = readLines.readLine()) != null) {
                if (readLine.isEmpty()) {
                    emitEvent(readEvent, readData, consumeEvent);
                    readEvent = "message";
                    readData.setLength(0);
                } else if (readLine.startsWith("event:")) {
                    readEvent = readLine.substring(6).trim();
                } else if (readLine.startsWith("data:")) {
                    if (!readData.isEmpty()) {
                        readData.append('\n');
                    }
                    readData.append(readLine.substring(5).trim());
                }
            }
            emitEvent(readEvent, readData, consumeEvent);
        } catch (IOException readError) {
            throw new IllegalStateException("Python chat stream interrupted", readError);
        }
    }

    private void emitEvent(
            String readName,
            StringBuilder readData,
            Consumer<StreamEvent> consumeEvent) {
        if (readData.isEmpty()) {
            return;
        }
        try {
            JsonNode readJson = objectMapper.readTree(readData.toString());
            consumeEvent.accept(new StreamEvent(readName, readJson));
        } catch (JsonProcessingException readError) {
            throw new IllegalStateException("Python chat emitted invalid JSON", readError);
        }
    }

    public record StreamRequest(
            String runId,
            Long epoch,
            String requestId,
            String userMessageId,
            String assistantMessageId,
            String input,
            List<Attachment> attachments,
            Map<String, Object> memory,
            String summary,
            List<HistoryMessage> history) {
    }

    public record Attachment(String fileId, String name, String mimeType) {
    }

    public record HistoryMessage(String role, String content) {
    }

    public record StreamEvent(String name, JsonNode data) {
    }
}
