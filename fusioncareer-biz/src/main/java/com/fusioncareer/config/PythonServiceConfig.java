package com.fusioncareer.config;

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

import java.time.Duration;

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
    public PythonServiceClient pythonServiceClient() {
        // 使用 JDK 11+ 内置的 HttpClient 作为底层实现，支持细粒度的超时控制
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));
        
        // 构造 RestClient
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
                return restClient.post()
                        .uri("/api/internal/resume/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(readRequest)
                        .retrieve()
                        .body(ResumeParseResponse.class);
            }

            @Override
            public JobStructureResponse structureJob(JobStructureRequest readRequest) {
                return restClient.post()
                        .uri("/api/internal/job/structure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(readRequest)
                        .retrieve()
                        .body(JobStructureResponse.class);
            }
        };
    }
}
