package com.fusioncareer.client;

import com.fusioncareer.dto.req.ResumeParseRequest;
import com.fusioncareer.dto.req.JobStructureRequest;
import com.fusioncareer.dto.res.JobStructureResponse;
import com.fusioncareer.dto.res.ResumeParseResponse;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * AI 算法服务端点接口 (基于 Spring 6 HTTP Interfaces)
 * <p>
 * 该接口的方法由 Spring 自动生成代理实现，直接对应 Python 端的 FastAPI 路由。
 * 后续具体的简历优化、OCR等接口都将声明在这里。
 *
 * @author Xiong Heng
 */
@HttpExchange("/api/internal")
public interface PythonServiceClient {

    /**
     * 框架搭建测试：探测 Python 端是否存活
     */
    @GetExchange("/health")
    String ping();

    @PostExchange("/resume/parse")
    ResumeParseResponse parseResume(@RequestBody ResumeParseRequest readRequest);

    @PostExchange("/job/structure")
    JobStructureResponse structureJob(@RequestBody JobStructureRequest readRequest);
}
