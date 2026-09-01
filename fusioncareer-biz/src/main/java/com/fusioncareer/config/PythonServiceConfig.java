package com.fusioncareer.config;

import com.fusioncareer.client.PythonServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

/**
 * 算法端 HTTP 客户端配置
 * <p>
 * 基于 Spring Boot 3 原生的 RestClient 与 HTTP Interfaces 特性，
 * 为 {@link PythonServiceClient} 生成代理 Bean，并配置超时时间等网络策略。
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

        // 适配到 HTTP Interfaces 工厂
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();

        // 代理生成客户端接口的实现
        return factory.createClient(PythonServiceClient.class);
    }
}
