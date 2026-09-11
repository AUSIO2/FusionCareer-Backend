package com.fusioncareer.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.dto.req.JobStructureRequest;
import com.fusioncareer.dto.req.ResumeParseRequest;
import com.fusioncareer.dto.res.JobStructureResponse;
import com.fusioncareer.dto.res.ResumeParseResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 算法端 HTTP 客户端配置
 * <p>
 * 使用显式 RestClient 调用，确保生产环境中的 JSON 请求体不会被代理层丢弃。
 *
 * @author Xiong Heng
 */
@Configuration
public class PythonServiceConfig {

    @Value("${python-service.base-url:http://127.0.0.1:8000}")
    private String baseUrl;

    @Value("${python-service.connect-timeout:5000}")
    private long connectTimeout;

    @Value("${python-service.read-timeout:60000}")
    private long readTimeout;

    @Value("${internal-service.token:}")
    private String internalToken;

    @Bean
    public PythonServiceClient pythonServiceClient(ObjectMapper readMapper) {
        HttpClient readHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(readHttpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();

        return new PythonServiceClient() {
            @Override
            public String ping() {
                return restClient.get()
                        .uri("/api/internal/health")
                        .retrieve()
                        .body(String.class);
            }

            @Override
            public ResumeParseResponse parseResume(ResumeParseRequest readRequest) {
                byte[] createBody = createBody(readRequest);
                return restClient.post()
                        .uri("/api/internal/resume/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .contentLength(createBody.length)
                        .body(createBody)
                        .retrieve()
                        .body(ResumeParseResponse.class);
            }

            @Override
            public JobStructureResponse structureJob(JobStructureRequest readRequest) {
                byte[] createBody = createBody(readRequest);
                return restClient.post()
                        .uri("/api/internal/job/structure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .contentLength(createBody.length)
                        .body(createBody)
                        .retrieve()
                        .body(JobStructureResponse.class);
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> readStructurePending() {
                return restClient.get()
                        .uri("/api/internal/job/structure-pending")
                        .retrieve()
                        .body(Map.class);
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> startStructurePending() {
                return restClient.post()
                        .uri("/api/internal/job/structure-pending")
                        .contentLength(0)
                        .retrieve()
                        .body(Map.class);
            }

            private byte[] createBody(Object readRequest) {
                try {
                    return readMapper.writeValueAsBytes(readRequest);
                } catch (JsonProcessingException readError) {
                    throw new IllegalArgumentException("无法序列化算法请求", readError);
                }
            }
        };
    }
}
