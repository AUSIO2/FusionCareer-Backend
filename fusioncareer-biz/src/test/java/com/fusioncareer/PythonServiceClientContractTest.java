package com.fusioncareer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.config.PythonServiceConfig;
import com.fusioncareer.dto.req.JobStructureRequest;
import com.fusioncareer.dto.req.ResumeParseRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonServiceClientContractTest {

    private final AtomicReference<String> readResumeBody = new AtomicReference<>();
    private final AtomicReference<String> readJobBody = new AtomicReference<>();
    private final AtomicReference<String> readContentType = new AtomicReference<>();
    private final AtomicReference<String> readContentLength = new AtomicReference<>();
    private final AtomicReference<String> readTransferEncoding = new AtomicReference<>();
    private final AtomicReference<String> readToken = new AtomicReference<>();
    private final AtomicReference<String> readProtocol = new AtomicReference<>();
    private final AtomicReference<String> readPendingMethod = new AtomicReference<>();
    private final AtomicLong readResumeDelayMs = new AtomicLong();
    private final AtomicLong readJobDelayMs = new AtomicLong();
    private final ObjectMapper readMapper = new ObjectMapper();
    private HttpServer readServer;
    private ExecutorService readExecutor;
    private PythonServiceClient readClient;

    @BeforeEach
    void createServer() throws IOException {
        readServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        readExecutor = Executors.newCachedThreadPool();
        readServer.setExecutor(readExecutor);
        readServer.createContext("/api/internal/resume/parse", readExchange -> {
            delayResponse(readResumeDelayMs.get());
            writeResponse(readExchange, readResumeBody,
                    "{\"profilePatch\":{\"major\":\"新闻学\"},\"resumePatch\":{\"skills\":\"Python\"},\"warnings\":[]}");
        });
        readServer.createContext("/api/internal/job/structure", readExchange -> {
            delayResponse(readJobDelayMs.get());
            writeResponse(readExchange, readJobBody, "{\"jobs\":[],\"warnings\":[]}");
        });
        readServer.createContext("/api/internal/health", readExchange ->
                writeResponse(readExchange, new AtomicReference<>(), "{\"status\":\"ok\"}"));
        readServer.createContext("/api/internal/job/structure-pending", readExchange -> {
            readPendingMethod.set(readExchange.getRequestMethod());
            writeResponse(readExchange, new AtomicReference<>(),
                    "{\"status\":\"RUNNING\",\"pendingCount\":7}");
        });
        readServer.start();

        readClient = createClient(2000L, 2000L);
    }

    private PythonServiceClient createClient(long readTimeoutMs, long readResumeTimeoutMs) {
        PythonServiceConfig createConfig = new PythonServiceConfig();
        ReflectionTestUtils.setField(createConfig, "baseUrl",
                "http://127.0.0.1:" + readServer.getAddress().getPort());
        ReflectionTestUtils.setField(createConfig, "connectTimeout", 2000L);
        ReflectionTestUtils.setField(createConfig, "readTimeout", readTimeoutMs);
        ReflectionTestUtils.setField(createConfig, "resumeReadTimeout", readResumeTimeoutMs);
        ReflectionTestUtils.setField(createConfig, "internalToken", "test-internal");
        return createConfig.pythonServiceClient(readMapper);
    }

    @AfterEach
    void stopServer() {
        readServer.stop(0);
        readExecutor.shutdownNow();
    }

    @Test
    void sendResumeNumbers() throws Exception {
        var readResult = readClient.parseResume(
                new ResumeParseRequest(700000000000000001L, 700000000000000002L));

        JsonNode readBody = readMapper.readTree(readResumeBody.get());
        assertThat(readBody.get("userId").isIntegralNumber()).isTrue();
        assertThat(readBody.get("fileId").isIntegralNumber()).isTrue();
        assertThat(readContentType.get()).startsWith("application/json");
        assertThat(Long.parseLong(readContentLength.get())).isPositive();
        assertThat(readTransferEncoding.get()).isNull();
        assertThat(readToken.get()).isEqualTo("test-internal");
        assertThat(readProtocol.get()).isEqualTo("HTTP/1.1");
        assertThat(readResult.getProfilePatch().getMajor()).isEqualTo("新闻学");
        assertThat(readResult.getResumePatch().getSkills()).isEqualTo("Python");
        assertThat(readResult.getWarnings()).isEmpty();
    }

    @Test
    void resumeTimeoutBudgetIsIndependentOfOtherAlgorithms() {
        readClient = createClient(100L, 2000L);
        readResumeDelayMs.set(500L);
        readJobDelayMs.set(500L);

        assertThat(readClient.parseResume(new ResumeParseRequest(1L, 2L))
                .getResumePatch().getSkills()).isEqualTo("Python");
        assertThat(readToken.get()).isEqualTo("test-internal");
        assertThatThrownBy(() -> readClient.structureJob(new JobStructureRequest()))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(readError -> assertThat(NestedExceptionUtils.getMostSpecificCause(readError))
                        .isInstanceOfAny(java.net.http.HttpTimeoutException.class,
                                java.util.concurrent.TimeoutException.class));
        assertThat(readClient.ping()).contains("ok");
    }

    @Test
    void resumeTimeoutRemainsBoundedByItsConfiguredBudget() {
        readClient = createClient(2000L, 100L);
        readResumeDelayMs.set(500L);

        assertThatThrownBy(() -> readClient.parseResume(new ResumeParseRequest(1L, 2L)))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(readError -> assertThat(NestedExceptionUtils.getMostSpecificCause(readError))
                        .isInstanceOfAny(java.net.http.HttpTimeoutException.class,
                                java.util.concurrent.TimeoutException.class));
    }

    @Test
    void sendJobEnums() throws Exception {
        JobStructureRequest createRequest = new JobStructureRequest();
        createRequest.setText("招聘编辑");
        readClient.structureJob(createRequest);

        JsonNode readBody = readMapper.readTree(readJobBody.get());
        assertThat(readBody.get("sourceType").asText()).isEqualTo("PLATFORM");
        assertThat(readBody.get("defaultStatus").asText()).isEqualTo("OFFLINE");
    }

    @Test
    void proxyStructurePending() {
        Map<String, Object> readStatus = readClient.readStructurePending();
        assertThat(readPendingMethod.get()).isEqualTo("GET");
        assertThat(readStatus.get("pendingCount")).isEqualTo(7);

        Map<String, Object> readStarted = readClient.startStructurePending();
        assertThat(readPendingMethod.get()).isEqualTo("POST");
        assertThat(readStarted.get("status")).isEqualTo("RUNNING");
        assertThat(readToken.get()).isEqualTo("test-internal");
    }

    private void writeResponse(
            HttpExchange readExchange,
            AtomicReference<String> updateBody,
            String readResponse) throws IOException {
        updateBody.set(new String(readExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        readContentType.set(readExchange.getRequestHeaders().getFirst("Content-Type"));
        readContentLength.set(readExchange.getRequestHeaders().getFirst("Content-Length"));
        readTransferEncoding.set(readExchange.getRequestHeaders().getFirst("Transfer-Encoding"));
        readToken.set(readExchange.getRequestHeaders().getFirst("X-Internal-Token"));
        readProtocol.set(readExchange.getProtocol());
        byte[] readBytes = readResponse.getBytes(StandardCharsets.UTF_8);
        readExchange.getResponseHeaders().set("Content-Type", "application/json");
        readExchange.sendResponseHeaders(200, readBytes.length);
        readExchange.getResponseBody().write(readBytes);
        readExchange.close();
    }

    private void delayResponse(long readDelayMs) throws IOException {
        try {
            Thread.sleep(readDelayMs);
        } catch (InterruptedException readInterrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("test response interrupted");
        }
    }
}
