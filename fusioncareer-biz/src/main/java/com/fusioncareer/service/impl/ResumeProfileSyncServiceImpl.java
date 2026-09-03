package com.fusioncareer.service.impl;

import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.client.dto.ResumeParseResponse;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.Gender;
import com.fusioncareer.enums.PoliticalStatus;
import com.fusioncareer.enums.ProfileUpdateStatus;
import com.fusioncareer.service.ProfileSyncOutcome;
import com.fusioncareer.service.ResumeProfileSyncService;
import com.fusioncareer.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 调用 Python 简历解析服务，并以补丁方式更新客观个人资料字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeProfileSyncServiceImpl implements ResumeProfileSyncService {

    private final PythonServiceClient pythonServiceClient;
    private final UserProfileService userProfileService;

    @Override
    public ProfileSyncOutcome syncProfile(Long userId, Resource resumeFile) {
        ResumeParseResponse response;
        try {
            response = pythonServiceClient.parseResume(resumeFile);
            if (response == null || !Integer.valueOf(200).equals(response.getCode()) || response.getData() == null) {
                String detail = response == null ? "算法服务无响应" : response.getMessage();
                throw new IllegalStateException(detail == null ? "算法服务返回无效结果" : detail);
            }
        } catch (RestClientException | IllegalStateException e) {
            log.warn("用户 {} 的简历算法识别失败: {}", userId, e.getMessage());
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.ALGORITHM_FAILED,
                    "简历已上传，但资料识别失败，请稍后重试或手动填写"
            );
        }

        UserProfileRequest profile = mapProfile(response.getData());
        if (!hasRecognizedField(profile)) {
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.NO_FIELDS_RECOGNIZED,
                    "简历已上传，但未识别到可更新的个人资料"
            );
        }

        try {
            userProfileService.patchProfile(userId, profile);
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.SUCCESS,
                    "已根据简历更新个人资料"
            );
        } catch (DataAccessException e) {
            log.error("用户 {} 的个人资料写入失败", userId, e);
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.PROFILE_UPDATE_FAILED,
                    "简历已上传并完成识别，但个人资料更新失败，请稍后重试"
            );
        }
    }

    private UserProfileRequest mapProfile(ResumeParseResponse.ResumeProfileData data) {
        UserProfileRequest request = new UserProfileRequest();

        setString(data.getRealName(), request::setRealName);
        setValue(genderFromCode(data.getGender()), request::setGender);
        setValue(parseExactDate(data.getBirthDate()), request::setBirthDate);
        setValue(politicalStatusFromCode(data.getPoliticalStatus()), request::setPoliticalStatus);
        setString(data.getPhone(), request::setPhone);
        setString(data.getEmail(), request::setEmail);
        setString(data.getWechat(), request::setWechat);
        setString(data.getHometown(), request::setHometown);
        setString(data.getGrade(), request::setGrade);
        setString(data.getMajor(), request::setMajor);
        setValue(eduLevelFromCode(data.getEduLevel()), request::setEduLevel);
        setString(data.getSupervisor(), request::setSupervisor);

        return request;
    }

    private void setString(String value, java.util.function.Consumer<String> setter) {
        if (value == null || value.isBlank()) return;
        setter.accept(value.trim());
    }

    private <T> void setValue(T value, java.util.function.Consumer<T> setter) {
        if (value == null) return;
        setter.accept(value);
    }

    private boolean hasRecognizedField(UserProfileRequest request) {
        return request.getRealName() != null
                || request.getGender() != null
                || request.getBirthDate() != null
                || request.getPoliticalStatus() != null
                || request.getPhone() != null
                || request.getEmail() != null
                || request.getWechat() != null
                || request.getHometown() != null
                || request.getGrade() != null
                || request.getMajor() != null
                || request.getEduLevel() != null
                || request.getSupervisor() != null;
    }

    private LocalDate parseExactDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Gender genderFromCode(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> Gender.MALE;
            case 2 -> Gender.FEMALE;
            case 3 -> Gender.OTHER;
            default -> null;
        };
    }

    private PoliticalStatus politicalStatusFromCode(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> PoliticalStatus.MASSES;
            case 2 -> PoliticalStatus.LEAGUE_MEMBER;
            case 3 -> PoliticalStatus.PARTY_MEMBER;
            case 4 -> PoliticalStatus.OTHER;
            default -> null;
        };
    }

    private EduLevel eduLevelFromCode(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> EduLevel.UNDERGRADUATE;
            case 2 -> EduLevel.ACADEMIC_MASTER;
            case 3 -> EduLevel.PROFESSIONAL_MASTER;
            case 4 -> EduLevel.DOCTORAL;
            default -> null;
        };
    }
}
