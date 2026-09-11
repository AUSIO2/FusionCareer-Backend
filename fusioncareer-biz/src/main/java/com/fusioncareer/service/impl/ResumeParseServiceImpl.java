package com.fusioncareer.service.impl;

import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.dto.req.ResumeParseRequest;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeParseResponse;
import com.fusioncareer.dto.res.ResumeUploadResponse;
import com.fusioncareer.enums.ResumeParseStatus;
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
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeParseServiceImpl implements ResumeParseService {

    private final ResumeFileService readFileService;
    private final UserProfileService updateProfileService;
    private final ResumeService updateResumeService;
    private final PythonServiceClient readPythonClient;

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
}
