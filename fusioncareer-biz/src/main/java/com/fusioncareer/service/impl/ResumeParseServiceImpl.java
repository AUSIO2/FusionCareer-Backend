package com.fusioncareer.service.impl;

import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.dto.req.ResumeParseRequest;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeParseResponse;
import com.fusioncareer.dto.res.ResumeUploadResponse;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeParseService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ResumeParseServiceImpl implements ResumeParseService {

    private final ResumeFileService readFileService;
    private final UserProfileService updateProfileService;
    private final ResumeService updateResumeService;
    private final PythonServiceClient readPythonClient;

    @Override
    @Transactional
    public ResumeUploadResponse updateResume(Long updateUserId, Long updateFileId) {
        readFileService.getOwnFile(updateUserId, updateFileId);
        ResumeParseResponse readResponse = readPythonClient.parseResume(
                new ResumeParseRequest(updateUserId, updateFileId));
        UserProfileRequest updateProfile = readResponse.getProfilePatch();
        ResumeRequest updateResume = readResponse.getResumePatch();
        List<String> readProfileFields = cleanPatch(updateProfile);
        List<String> readResumeFields = cleanPatch(updateResume);
        if (updateProfile != null && !readProfileFields.isEmpty()) {
            updateProfileService.saveOrUpdateProfile(updateUserId, updateProfile);
        }
        if (updateResume != null && !readResumeFields.isEmpty()) {
            updateResumeService.saveOrUpdateResume(updateUserId, updateResume);
        }
        ResumeUploadResponse createResponse = new ResumeUploadResponse();
        createResponse.setParseStatus("SUCCESS");
        createResponse.setUpdatedProfileFields(readProfileFields);
        createResponse.setUpdatedResumeFields(readResumeFields);
        createResponse.setMessage("已使用简历解析结果更新资料");
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
