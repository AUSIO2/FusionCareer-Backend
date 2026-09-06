package com.fusioncareer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.client.dto.JobNormalizeAlgorithmResponse;
import com.fusioncareer.dto.req.JobDescriptionNormalizeRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDescriptionNormalizationServiceImplTest {

    @Test
    void normalizeConvertsAlgorithmDataToStandardJobRequest() {
        StubPythonServiceClient client = new StubPythonServiceClient();
        JobNormalizeAlgorithmResponse response = new JobNormalizeAlgorithmResponse();
        response.setCode(200);
        response.setMessage("success");
        response.setData(Map.of(
                "position_name", "新媒体运营实习生",
                "company_name", "复新传媒",
                "job_category", "MEDIA",
                "headcount", 2
        ));
        client.response = response;
        JobDescriptionNormalizationServiceImpl service =
                new JobDescriptionNormalizationServiceImpl(client, new ObjectMapper());

        JobPostRequest result = service.normalize("  原始岗位描述  ");

        assertThat(client.request.getRawDescription()).isEqualTo("原始岗位描述");
        assertThat(result.getPositionName()).isEqualTo("新媒体运营实习生");
        assertThat(result.getCompanyName()).isEqualTo("复新传媒");
        assertThat(result.getJobCategory()).isEqualTo(JobCategory.MEDIA);
        assertThat(result.getHeadcount()).isEqualTo(2);
    }

    @Test
    void normalizeRejectsEmptyAlgorithmResult() {
        StubPythonServiceClient client = new StubPythonServiceClient();
        JobNormalizeAlgorithmResponse response = new JobNormalizeAlgorithmResponse();
        response.setCode(200);
        response.setData(Map.of());
        client.response = response;
        JobDescriptionNormalizationServiceImpl service =
                new JobDescriptionNormalizationServiceImpl(client, new ObjectMapper());

        assertThatThrownBy(() -> service.normalize("原始岗位描述"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("岗位信息处理服务未返回有效结果");
    }

    @Test
    void normalizeReportsAlgorithmConnectionFailure() {
        StubPythonServiceClient client = new StubPythonServiceClient();
        client.failure = new RestClientException("timeout");
        JobDescriptionNormalizationServiceImpl service =
                new JobDescriptionNormalizationServiceImpl(client, new ObjectMapper());

        assertThatThrownBy(() -> service.normalize("原始岗位描述"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("岗位信息处理服务暂时不可用，请稍后重试");
    }

    private static class StubPythonServiceClient implements PythonServiceClient {
        private JobDescriptionNormalizeRequest request;
        private JobNormalizeAlgorithmResponse response;
        private RestClientException failure;

        @Override
        public String ping() {
            return "pong";
        }

        @Override
        public JobNormalizeAlgorithmResponse normalizeJob(JobDescriptionNormalizeRequest request) {
            this.request = request;
            if (failure != null) throw failure;
            return response;
        }
    }
}
