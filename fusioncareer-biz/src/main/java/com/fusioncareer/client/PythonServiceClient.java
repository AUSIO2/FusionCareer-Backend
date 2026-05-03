package com.fusioncareer.client;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * AI 算法服务端点接口 (基于 Spring 6 HTTP Interfaces)
 * <p>
 * 该接口的方法由 Spring 自动生成代理实现，直接对应 Python 端的 FastAPI 路由。
 * 后续具体的简历优化、OCR等接口都将声明在这里。
 *
 * @author Xiong Heng
 */
@HttpExchange("/api/v1")
public interface PythonServiceClient {

    /**
     * 框架搭建测试：探测 Python 端是否存活
     */
    @GetExchange("/ping")
    String ping();
    
    // TODO: 后续在这里添加具体业务接口（如 @PostExchange("/resume/optimize")）
}
