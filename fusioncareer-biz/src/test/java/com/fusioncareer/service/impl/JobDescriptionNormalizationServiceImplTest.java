package com.fusioncareer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.client.dto.JobNormalizeAlgorithmResponse;
import com.fusioncareer.dto.req.JobDescriptionNormalizeRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.springframework.web.client.RestClientException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDescriptionNormalizationServiceImplTest {

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsAmbiguousOrInvalidAlgorithmFields(Map<String, Object> data) {
        assertThatThrownBy(() -> serviceWith(data).normalize("岗位描述"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("岗位信息处理结果格式不正确");
    }

    static Stream<Map<String, Object>> invalidPayloads() {
        return Stream.of(
                Map.of("positionName", "编辑", "jobCategory", 2),
                Map.of("positionName", "编辑", "jobCategory", "2"),
                Map.of("positionName", "编辑", "headcount", 2.5),
                Map.of("positionName", "编辑", "position_name", "记者"),
                Map.of("positionName", "编辑", "workStartDate", "2026-99-99"),
                Map.of("positionName", "   ")
        );
    }

    @Test
    void preservesDatesAndIgnoresAlgorithmPublicationControls() {
        JobPostRequest result = serviceWith(Map.of(
                "position_name", " 编辑 ", "work_start_date", "2026-09-06",
                "status", "PUBLISHED", "source_type", "CRAWL"
        )).normalize("岗位描述");
        assertThat(result.getPositionName()).isEqualTo("编辑");
        assertThat(result.getWorkStartDate()).isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(result.getStatus()).isNull();
        assertThat(result.getSourceType()).isNull();
    }

    private JobDescriptionNormalizationServiceImpl serviceWith(Map<String, Object> data) {
        StubPythonServiceClient client = new StubPythonServiceClient();
        client.response = new JobNormalizeAlgorithmResponse();
        client.response.setCode(200);
        client.response.setData(data);
        return new JobDescriptionNormalizationServiceImpl(client,
                Jackson2ObjectMapperBuilder.json().build());
    }

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
