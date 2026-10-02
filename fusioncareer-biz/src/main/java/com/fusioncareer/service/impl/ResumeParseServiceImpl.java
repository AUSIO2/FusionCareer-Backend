package com.fusioncareer.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.dto.req.ResumeParseRequest;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeParseResponse;
import com.fusioncareer.dto.res.ResumeUploadResponse;
import com.fusioncareer.enums.ResumeParseStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeParseService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import lombok.extern.slf4j.Slf4j;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeParseServiceImpl implements ResumeParseService {

    private final ResumeFileService readFileService;
    private final UserProfileService updateProfileService;
    private final ResumeService updateResumeService;
    private final PythonServiceClient readPythonClient;
    private final ObjectMapper objectMapper;

    @Override
    public ParsedPatches parseForProposal(Long userId, Long fileId) {
        readFileService.getOwnFile(userId, fileId);
        ResumeParseResponse readResponse;
        try {
            readResponse = readPythonClient.parseResume(new ResumeParseRequest(userId, fileId));
        } catch (RestClientException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "简历解析服务暂时不可用");
        }
        if (readResponse == null) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "简历解析服务未返回结果");
        }
        Map<String, Object> readProfileSet = readPatchValues(
                readResponse.getProfilePatch(), true);
        Map<String, Object> readResumeSet = readPatchValues(
                readResponse.getResumePatch(), false);
        if (readProfileSet.isEmpty() && readResumeSet.isEmpty()) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "未识别到可更新的资料字段");
        }
        List<String> readWarnings = readResponse.getWarnings() == null
                ? List.of()
                : readResponse.getWarnings().stream()
                .filter(readWarning -> readWarning != null && !readWarning.isBlank())
                .limit(20)
                .map(readWarning -> readWarning.length() > 256
                        ? readWarning.substring(0, 256) : readWarning)
                .toList();
        return new ParsedPatches(readProfileSet, readResumeSet, readWarnings);
    }

    @Override
    @Transactional
    public ResumeUploadResponse updateResume(Long updateUserId, Long updateFileId) {
        readFileService.getOwnFile(updateUserId, updateFileId);
        ResumeParseResponse readResponse;
        try {
            readResponse = readPythonClient.parseResume(
                    new ResumeParseRequest(updateUserId, updateFileId));
        } catch (RestClientException readError) {
            log.warn("简历算法调用失败: {}", readError.getClass().getSimpleName());
            return buildResponse(ResumeParseStatus.ALGORITHM_FAILED,
                    "简历已保存，但算法服务暂时不可用");
        }
        if (readResponse == null) {
            return buildResponse(ResumeParseStatus.ALGORITHM_FAILED,
                    "简历已保存，但算法服务未返回结果");
        }
        UserProfileRequest updateProfile = readResponse.getProfilePatch();
        ResumeRequest updateResume = readResponse.getResumePatch();
        List<String> readProfileFields = cleanPatch(updateProfile);
        List<String> readResumeFields = cleanPatch(updateResume);
        if (readProfileFields.isEmpty() && readResumeFields.isEmpty()) {
            return buildResponse(ResumeParseStatus.NO_FIELDS, "未识别到可更新的资料字段");
        }
        if (updateProfile != null && !readProfileFields.isEmpty()) {
            updateProfileService.saveOrUpdateProfile(updateUserId, updateProfile);
        }
        if (updateResume != null && !readResumeFields.isEmpty()) {
            updateResumeService.saveOrUpdateResume(updateUserId, updateResume);
        }
        ResumeUploadResponse createResponse = buildResponse(
                ResumeParseStatus.SUCCESS, "已使用简历解析结果更新资料");
        createResponse.setUpdatedProfileFields(readProfileFields);
        createResponse.setUpdatedResumeFields(readResumeFields);
        return createResponse;
    }

    private ResumeUploadResponse buildResponse(ResumeParseStatus readStatus, String readMessage) {
        ResumeUploadResponse createResponse = new ResumeUploadResponse();
        createResponse.setParseStatus(readStatus);
        createResponse.setMessage(readMessage);
        return createResponse;
    }

    private List<String> cleanPatch(Object updatePatch) {
        List<String> readFields = new ArrayList<>();
        if (updatePatch == null) {
            return readFields;
        }
        BeanWrapper updateWrapper = PropertyAccessorFactory.forBeanPropertyAccess(updatePatch);
        for (PropertyDescriptor readProperty : updateWrapper.getPropertyDescriptors()) {
            String readName = readProperty.getName();
            if ("class".equals(readName) || !updateWrapper.isReadableProperty(readName)) {
                continue;
            }
            Object readValue = updateWrapper.getPropertyValue(readName);
            if (readValue instanceof String readText && readText.isBlank()) {
                updateWrapper.setPropertyValue(readName, null);
                continue;
            }
            if (readValue != null) {
                readFields.add(readName);
            }
        }
        return readFields;
    }

    private Map<String, Object> readPatchValues(Object readPatch, boolean readProfile) {
        Map<String, Object> readValues = new LinkedHashMap<>();
        if (readPatch == null) {
            return readValues;
        }
        BeanWrapper readWrapper = PropertyAccessorFactory.forBeanPropertyAccess(readPatch);
        for (PropertyDescriptor readProperty : readWrapper.getPropertyDescriptors()) {
            String readName = readProperty.getName();
            if ("class".equals(readName) || !readWrapper.isReadableProperty(readName)) {
                continue;
            }
            Object readValue = readWrapper.getPropertyValue(readName);
            if (readValue == null || readValue instanceof String readText && readText.isBlank()) {
                continue;
            }
            if (readValue instanceof Enum<?> readEnum) {
                readValues.put(readName, readEnum.name());
            } else if (readProfile && "intentionCity".equals(readName)) {
                readValues.put(readName, readCities(readValue));
            } else {
                readValues.put(readName, readValue);
            }
        }
        return readValues;
    }

    private List<String> readCities(Object readValue) {
        if (readValue instanceof List<?> readCities) {
            return readCities.stream().map(String::valueOf).toList();
        }
        try {
            return objectMapper.readValue(String.valueOf(readValue), new TypeReference<>() { });
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "解析结果中的意向城市格式无效");
        }
    }
}
