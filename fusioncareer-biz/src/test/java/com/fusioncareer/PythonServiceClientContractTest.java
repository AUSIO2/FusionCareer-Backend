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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PythonServiceClientContractTest {

    private final AtomicReference<String> readResumeBody = new AtomicReference<>();
    private final AtomicReference<String> readJobBody = new AtomicReference<>();
    private final AtomicReference<String> readContentType = new AtomicReference<>();
    private final AtomicReference<String> readToken = new AtomicReference<>();
    private final ObjectMapper readMapper = new ObjectMapper();
    private HttpServer readServer;
    private PythonServiceClient readClient;

    @BeforeEach
    void createServer() throws IOException {
        readServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        readServer.createContext("/api/internal/resume/parse", readExchange ->
                writeResponse(readExchange, readResumeBody,
                        "{\"profilePatch\":{},\"resumePatch\":{},\"warnings\":[]}"));
        readServer.createContext("/api/internal/job/structure", readExchange ->
                writeResponse(readExchange, readJobBody, "{\"jobs\":[],\"warnings\":[]}"));
        readServer.start();

        PythonServiceConfig createConfig = new PythonServiceConfig();
        ReflectionTestUtils.setField(createConfig, "baseUrl",
                "http://127.0.0.1:" + readServer.getAddress().getPort());
        ReflectionTestUtils.setField(createConfig, "connectTimeout", 2000L);
        ReflectionTestUtils.setField(createConfig, "readTimeout", 2000L);
        ReflectionTestUtils.setField(createConfig, "internalToken", "test-internal");
        readClient = createConfig.pythonServiceClient();
    }

    @AfterEach
    void stopServer() {
        readServer.stop(0);
    }

    @Test
    void sendResumeNumbers() throws Exception {
        readClient.parseResume(new ResumeParseRequest(700000000000000001L, 700000000000000002L));

        JsonNode readBody = readMapper.readTree(readResumeBody.get());
        assertThat(readBody.get("userId").isIntegralNumber()).isTrue();
        assertThat(readBody.get("fileId").isIntegralNumber()).isTrue();
        assertThat(readContentType.get()).startsWith("application/json");
        assertThat(readToken.get()).isEqualTo("test-internal");
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

    private void writeResponse(
            HttpExchange readExchange,
            AtomicReference<String> updateBody,
            String readResponse) throws IOException {
        updateBody.set(new String(readExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        readContentType.set(readExchange.getRequestHeaders().getFirst("Content-Type"));
        readToken.set(readExchange.getRequestHeaders().getFirst("X-Internal-Token"));
        byte[] readBytes = readResponse.getBytes(StandardCharsets.UTF_8);
        readExchange.getResponseHeaders().set("Content-Type", "application/json");
        readExchange.sendResponseHeaders(200, readBytes.length);
        readExchange.getResponseBody().write(readBytes);
        readExchange.close();
    }
}
