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
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

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
        } catch (Exception e) {
            log.warn("用户 {} 的简历算法识别失败: {}", userId, e.getMessage());
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.ALGORITHM_FAILED,
                    List.of(),
                    "简历已上传，但资料识别失败，请稍后重试或手动填写"
            );
        }

        MappingResult mapping = mapProfile(response.getData());
        if (mapping.fields().isEmpty()) {
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.NO_FIELDS_RECOGNIZED,
                    List.of(),
                    "简历已上传，但未识别到可更新的个人资料"
            );
        }

        try {
            userProfileService.saveOrUpdateProfile(userId, mapping.request());
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.SUCCESS,
                    mapping.fields(),
                    "已根据简历更新个人资料"
            );
        } catch (Exception e) {
            log.error("用户 {} 的个人资料写入失败", userId, e);
            return new ProfileSyncOutcome(
                    ProfileUpdateStatus.PROFILE_UPDATE_FAILED,
                    List.of(),
                    "简历已上传并完成识别，但个人资料更新失败，请稍后重试"
            );
        }
    }

    private MappingResult mapProfile(ResumeParseResponse.ResumeProfileData data) {
        UserProfileRequest request = new UserProfileRequest();
        List<String> fields = new ArrayList<>();

        setString(data.getRealName(), request::setRealName, "realName", fields);
        setValue(genderFromCode(data.getGender()), request::setGender, "gender", fields);
        setValue(parseExactDate(data.getBirthDate()), request::setBirthDate, "birthDate", fields);
        setValue(politicalStatusFromCode(data.getPoliticalStatus()), request::setPoliticalStatus,
                "politicalStatus", fields);
        setString(data.getPhone(), request::setPhone, "phone", fields);
        setString(data.getEmail(), request::setEmail, "email", fields);
        setString(data.getWechat(), request::setWechat, "wechat", fields);
        setString(data.getHometown(), request::setHometown, "hometown", fields);
        setString(data.getGrade(), request::setGrade, "grade", fields);
        setString(data.getMajor(), request::setMajor, "major", fields);
        setValue(eduLevelFromCode(data.getEduLevel()), request::setEduLevel, "eduLevel", fields);
        setString(data.getSupervisor(), request::setSupervisor, "supervisor", fields);

        return new MappingResult(request, List.copyOf(fields));
    }

    private void setString(String value, java.util.function.Consumer<String> setter,
                           String field, List<String> fields) {
        if (value == null || value.isBlank()) return;
        setter.accept(value.trim());
        fields.add(field);
    }

    private <T> void setValue(T value, java.util.function.Consumer<T> setter,
                              String field, List<String> fields) {
        if (value == null) return;
        setter.accept(value);
        fields.add(field);
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

    private record MappingResult(UserProfileRequest request, List<String> fields) {
    }
}
