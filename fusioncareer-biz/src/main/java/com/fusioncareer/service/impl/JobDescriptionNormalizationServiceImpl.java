package com.fusioncareer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.client.dto.JobNormalizeAlgorithmResponse;
import com.fusioncareer.dto.req.JobDescriptionNormalizeRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.JobDescriptionNormalizationService;
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
public class JobDescriptionNormalizationServiceImpl implements JobDescriptionNormalizationService {

    private final PythonServiceClient pythonServiceClient;
    private final ObjectMapper objectMapper;

    public JobDescriptionNormalizationServiceImpl(PythonServiceClient pythonServiceClient, ObjectMapper objectMapper) {
        this.pythonServiceClient = pythonServiceClient;
        // 仅收紧算法结果转换，保留应用的日期模块，不影响其他接口。
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

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
        data.forEach((key, value) -> {
            String field = toCamelCase(key);
            // 来源与发布状态由管理员发布流程决定，算法只负责提取岗位内容。
            if (Set.of("status", "sourceType").contains(field)) return;
            if (normalized.containsKey(field)) {
                throw new IllegalArgumentException("duplicate normalized field: " + field);
            }
            normalized.put(field, value instanceof String text ? text.strip() : value);
        });
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
