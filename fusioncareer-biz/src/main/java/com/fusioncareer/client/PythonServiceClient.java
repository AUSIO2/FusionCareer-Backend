package com.fusioncareer.client;

import com.fusioncareer.dto.req.ResumeParseRequest;
import com.fusioncareer.dto.req.JobStructureRequest;
import com.fusioncareer.dto.res.JobStructureResponse;
import com.fusioncareer.dto.res.ResumeParseResponse;

import java.util.Map;
/** AI 算法服务客户端。 */
public interface PythonServiceClient {

    /**
     * 框架搭建测试：探测 Python 端是否存活
     */
    String ping();

    ResumeParseResponse parseResume(ResumeParseRequest readRequest);

    JobStructureResponse structureJob(JobStructureRequest readRequest);

    Map<String, Object> readStructurePending();

    Map<String, Object> startStructurePending();
}
