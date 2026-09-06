package com.fusioncareer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.client.dto.JobNormalizeAlgorithmResponse;
import com.fusioncareer.dto.req.JobDescriptionNormalizeRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.JobDescriptionNormalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 调用算法服务，将原始岗位描述转换为后端标准岗位字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobDescriptionNormalizationServiceImpl implements JobDescriptionNormalizationService {

    private static final Set<String> ENUM_FIELDS = Set.of(
            "sourceType", "jobCategory", "jobSubCategory", "recruitType", "workDurationType",
            "workPeriodType", "workMode", "reqEduLevel", "status"
    );

    private final PythonServiceClient pythonServiceClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobPostRequest normalize(String rawDescription) {
        JobDescriptionNormalizeRequest algorithmRequest = new JobDescriptionNormalizeRequest();
        algorithmRequest.setRawDescription(rawDescription.trim());

        JobNormalizeAlgorithmResponse response;
        try {
            response = pythonServiceClient.normalizeJob(algorithmRequest);
        } catch (RestClientException e) {
            log.warn("岗位描述算法调用失败: {}", e.getMessage());
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "岗位信息处理服务暂时不可用，请稍后重试");
        }

        if (response == null
                || !Integer.valueOf(ResultCode.SUCCESS.getCode()).equals(response.getCode())
                || response.getData() == null
                || response.getData().isEmpty()) {
            String message = response == null ? null : response.getMessage();
            log.warn("岗位描述算法返回无效结果: {}", message);
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "岗位信息处理服务未返回有效结果");
        }

        try {
            Map<String, Object> normalizedData = normalizeFieldNames(response.getData());
            rejectNumericEnums(normalizedData);
            JobPostRequest result = objectMapper.convertValue(normalizedData, JobPostRequest.class);
            if (isBlank(result.getPositionName())
                    && isBlank(result.getCompanyName())
                    && isBlank(result.getJobDesc())) {
                throw new IllegalArgumentException("no core job fields recognized");
            }
            return result;
        } catch (IllegalArgumentException e) {
            log.warn("岗位描述算法字段无法转换: {}", e.getMessage());
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "岗位信息处理结果格式不正确");
        }
    }

    private Map<String, Object> normalizeFieldNames(Map<String, Object> data) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        data.forEach((key, value) -> normalized.put(toCamelCase(key), value));
        return normalized;
    }

    private String toCamelCase(String value) {
        StringBuilder result = new StringBuilder();
        boolean uppercaseNext = false;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                uppercaseNext = true;
            } else {
                result.append(uppercaseNext ? Character.toUpperCase(character) : character);
                uppercaseNext = false;
            }
        }
        return result.toString();
    }

    private void rejectNumericEnums(Map<String, Object> data) {
        ENUM_FIELDS.forEach(field -> {
            if (data.get(field) instanceof Number) {
                throw new IllegalArgumentException("numeric enum is not supported: " + field);
            }
        });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
