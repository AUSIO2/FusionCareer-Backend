package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.req.PersonalSpacePatchRequest;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.Gender;
import com.fusioncareer.enums.Mindset;
import com.fusioncareer.enums.PoliticalStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 当前用户个人空间的字段级写入入口。
 */
@Service
@RequiredArgsConstructor
public class PersonalSpaceMutationService {

    private static final int RESUME_TEXT_LIMIT = 16_000;

    private static final Map<String, FieldSpec> PROFILE_FIELDS = Map.ofEntries(
            defineField("realName", "real_name", String.class, 32),
            defineField("gender", "gender", Gender.class),
            defineField("birthDate", "birth_date", LocalDate.class),
            defineField("politicalStatus", "political_status", PoliticalStatus.class),
            defineField("phone", "phone", String.class, 20),
            defineField("email", "email", String.class, 64),
            defineField("wechat", "wechat", String.class, 64),
            defineField("hometown", "hometown", String.class, 64),
            defineField("grade", "grade", String.class, 16),
            defineField("major", "major", String.class, 64),
            defineField("eduLevel", "edu_level", EduLevel.class),
            defineField("supervisor", "supervisor", String.class, 64),
            defineField("intentionOrder", "intention_order", String.class, 64),
            defineField("intentionCity", "intention_city", List.class),
            defineField("intentionDream", "intention_dream", String.class, 256),
            defineField("mindset", "mindset", Mindset.class)
    );

    private static final Map<String, FieldSpec> RESUME_FIELDS = Map.ofEntries(
            defineField("personalIntro", "personal_intro", String.class, 300),
            defineField("basicInfo", "basic_info", String.class, RESUME_TEXT_LIMIT),
            defineField("education", "education", String.class, RESUME_TEXT_LIMIT),
            defineField("internship", "internship", String.class, RESUME_TEXT_LIMIT),
            defineField("campus", "campus", String.class, RESUME_TEXT_LIMIT),
            defineField("awards", "awards", String.class, RESUME_TEXT_LIMIT),
            defineField("skills", "skills", String.class, RESUME_TEXT_LIMIT),
            defineField("portfolio", "portfolio", String.class, RESUME_TEXT_LIMIT),
            defineField("remark", "remark", String.class, RESUME_TEXT_LIMIT)
    );

    private final UserService readUserService;
    private final UserProfileService profileService;
    private final ResumeService resumeService;
    private final UserChangeService changeService;
    private final ObjectMapper objectMapper;

    @Transactional
    public UserProfileResponse patchProfile(Long updateUserId, PersonalSpacePatchRequest readRequest) {
        return patchProfile(updateUserId, readRequest, true);
    }

    private UserProfileResponse patchProfile(
            Long updateUserId,
            PersonalSpacePatchRequest readRequest,
            boolean saveHistory) {
        requireAccount(updateUserId);
        UserProfileEntity readProfile = profileService.getById(updateUserId);
        assertVersion("个人资料", readRequest.getExpectedVersion(),
                readProfile == null ? null : readProfile.getVersion());

        UserProfileEntity updateProfile = readProfile == null ? new UserProfileEntity() : readProfile;
        updateProfile.setUserId(updateUserId);
        PatchValues updatePatch = applyPatch(updateProfile, readRequest, PROFILE_FIELDS);
        validateProfile(updateProfile);
        if (updatePatch.afterFields().isEmpty()) {
            return profileService.getProfile(updateUserId);
        }
        if (readProfile == null) {
            updateProfile.setVersion(0L);
            saveNew("个人资料", () -> profileService.save(updateProfile));
        } else {
            updateResource("个人资料", updateUserId, readRequest.getExpectedVersion(),
                    updatePatch.afterFields(), PROFILE_FIELDS, profileService);
        }
        if (saveHistory) {
            recordChange(
                    updateUserId, "PROFILE_UI", ChangeResourceType.PROFILE,
                    readProfile == null, readRequest.getExpectedVersion(), updatePatch, "修改个人资料");
        }
        return profileService.getProfile(updateUserId);
    }

    @Transactional
    public ResumeResponse patchResume(Long updateUserId, PersonalSpacePatchRequest readRequest) {
        return patchResume(updateUserId, readRequest, true);
    }

    /**
     * 校验并计算 Agent 资料提案，但不写入业务表。
     */
    @Transactional(readOnly = true)
    public PreparedChange prepareProfileProposal(
            Long readUserId,
            Map<String, Object> readSet,
            List<String> readClear) {
        requireAccount(readUserId);
        UserProfileEntity readProfile = profileService.getById(readUserId);
        UserProfileEntity updateProfile = copyProfile(readProfile, readUserId);
        PersonalSpacePatchRequest readRequest = buildProposalRequest(
                readProfile == null ? 0L : readProfile.getVersion(), readSet, readClear);
        PatchValues updatePatch = applyPatch(updateProfile, readRequest, PROFILE_FIELDS);
        validateProfile(updateProfile);
        return buildPreparedChange(
                readUserId,
                ChangeResourceType.PROFILE,
                readProfile != null,
                readProfile == null ? null : readProfile.getVersion(),
                updatePatch);
    }

    /**
     * 校验并计算 Agent 结构化简历提案，但不写入业务表。
     */
    @Transactional(readOnly = true)
    public PreparedChange prepareResumeProposal(
            Long readUserId,
            Map<String, Object> readSet,
            List<String> readClear) {
        requireAccount(readUserId);
        ResumeEntity readResume = resumeService.getById(readUserId);
        ResumeEntity updateResume = copyResume(readResume, readUserId);
        PersonalSpacePatchRequest readRequest = buildProposalRequest(
                readResume == null ? 0L : readResume.getVersion(), readSet, readClear);
        PatchValues updatePatch = applyPatch(updateResume, readRequest, RESUME_FIELDS);
        return buildPreparedChange(
                readUserId,
                ChangeResourceType.RESUME,
                readResume != null,
                readResume == null ? null : readResume.getVersion(),
                updatePatch);
    }

    private ResumeResponse patchResume(
            Long updateUserId,
            PersonalSpacePatchRequest readRequest,
            boolean saveHistory) {
        requireAccount(updateUserId);
        ResumeEntity readResume = resumeService.getById(updateUserId);
        assertVersion("简历", readRequest.getExpectedVersion(),
                readResume == null ? null : readResume.getVersion());

        ResumeEntity updateResume = readResume == null ? new ResumeEntity() : readResume;
        updateResume.setUserId(updateUserId);
        PatchValues updatePatch = applyPatch(updateResume, readRequest, RESUME_FIELDS);
        if (updatePatch.afterFields().isEmpty()) {
            return resumeService.getResume(updateUserId);
        }
        if (readResume == null) {
            updateResume.setVersion(0L);
            saveNew("简历", () -> resumeService.save(updateResume));
        } else {
            updateResource("简历", updateUserId, readRequest.getExpectedVersion(),
                    updatePatch.afterFields(), RESUME_FIELDS, resumeService);
        }
        if (saveHistory) {
            recordChange(
                    updateUserId, "RESUME_UI", ChangeResourceType.RESUME,
                    readResume == null, readRequest.getExpectedVersion(), updatePatch, "修改结构化简历");
        }
        return resumeService.getResume(updateUserId);
    }

    @Transactional
    public Long restoreSnapshot(
            Long updateUserId,
            ChangeResourceType readResourceType,
            Long readVersion,
            boolean updateExists,
            Map<String, Object> updateFields) {
        if (readResourceType == ChangeResourceType.PROFILE) {
            if (!updateExists) {
                return deleteProfile(updateUserId, readVersion);
            }
            PersonalSpacePatchRequest updateRequest = buildRequest(
                    readVersion, updateFields, readResourceType);
            return patchProfile(updateUserId, updateRequest, false).getVersion();
        }
        if (readResourceType == ChangeResourceType.RESUME) {
            if (!updateExists) {
                return deleteResume(updateUserId, readVersion);
            }
            PersonalSpacePatchRequest updateRequest = buildRequest(
                    readVersion, updateFields, readResourceType);
            return patchResume(updateUserId, updateRequest, false).getVersion();
        }
        throw buildInvalid("不支持回退资源: " + readResourceType);
    }

    private PersonalSpacePatchRequest buildRequest(
            Long readVersion,
            Map<String, Object> readFields,
            ChangeResourceType readResourceType) {
        PersonalSpacePatchRequest updateRequest = new PersonalSpacePatchRequest();
        updateRequest.setExpectedVersion(readVersion == null ? 0L : readVersion);
        Map<String, Object> updateSet = new LinkedHashMap<>();
        List<String> updateClear = new ArrayList<>();
        readFields.forEach((readField, readValue) -> {
            if (readValue == null) {
                updateClear.add(readField);
                return;
            }
            if (readResourceType == ChangeResourceType.PROFILE
                    && "intentionCity".equals(readField)
                    && readValue instanceof String readCities) {
                try {
                    updateSet.put(readField, objectMapper.readValue(readCities, List.class));
                } catch (JsonProcessingException readError) {
                    throw buildInvalid("历史 intentionCity 格式错误");
                }
                return;
            }
            updateSet.put(readField, readValue);
        });
        updateRequest.setSet(updateSet);
        updateRequest.setClear(updateClear);
        return updateRequest;
    }

    private PersonalSpacePatchRequest buildProposalRequest(
            Long readVersion,
            Map<String, Object> readSet,
            List<String> readClear) {
        PersonalSpacePatchRequest createRequest = new PersonalSpacePatchRequest();
        createRequest.setExpectedVersion(readVersion);
        createRequest.setSet(readSet == null ? Map.of() : new LinkedHashMap<>(readSet));
        createRequest.setClear(readClear == null ? List.of() : List.copyOf(readClear));
        return createRequest;
    }

    private UserProfileEntity copyProfile(UserProfileEntity readProfile, Long readUserId) {
        UserProfileEntity updateProfile = new UserProfileEntity();
        if (readProfile != null) {
            BeanUtils.copyProperties(readProfile, updateProfile);
        }
        updateProfile.setUserId(readUserId);
        return updateProfile;
    }

    private ResumeEntity copyResume(ResumeEntity readResume, Long readUserId) {
        ResumeEntity updateResume = new ResumeEntity();
        if (readResume != null) {
            BeanUtils.copyProperties(readResume, updateResume);
        }
        updateResume.setUserId(readUserId);
        return updateResume;
    }

    private PreparedChange buildPreparedChange(
            Long readUserId,
            ChangeResourceType readResourceType,
            boolean readExists,
            Long readVersion,
            PatchValues readPatch) {
        if (readPatch.afterFields().isEmpty()) {
            throw buildInvalid("提案没有产生实际变化");
        }
        return new PreparedChange(
                readResourceType,
                readUserId.toString(),
                readExists ? ChangeOperation.PATCH : ChangeOperation.CREATE,
                new ArrayList<>(readPatch.afterFields().keySet()),
                readVersion,
                readExists,
                true,
                readPatch.beforeFields(),
                readPatch.afterFields());
    }

    private Long deleteProfile(Long updateUserId, Long readVersion) {
        if (readVersion == null || !profileService.remove(
                new LambdaQueryWrapper<UserProfileEntity>()
                        .eq(UserProfileEntity::getUserId, updateUserId)
                        .eq(UserProfileEntity::getVersion, readVersion))) {
            throw buildConflict("个人资料");
        }
        return null;
    }

    private Long deleteResume(Long updateUserId, Long readVersion) {
        if (readVersion == null || !resumeService.remove(
                new LambdaQueryWrapper<ResumeEntity>()
                        .eq(ResumeEntity::getUserId, updateUserId)
                        .eq(ResumeEntity::getVersion, readVersion))) {
            throw buildConflict("简历");
        }
        return null;
    }

    private PatchValues applyPatch(
            Object updateResource,
            PersonalSpacePatchRequest readRequest,
            Map<String, FieldSpec> readFields) {
        Map<String, Object> readSet = readRequest.getSet() == null ? Map.of() : readRequest.getSet();
        List<String> readClear = readRequest.getClear() == null ? List.of() : readRequest.getClear();
        if (readSet.isEmpty() && readClear.isEmpty()) {
            throw buildInvalid("set 和 clear 至少需要提供一个字段");
        }

        Set<String> readClearFields = new LinkedHashSet<>();
        for (String readField : readClear) {
            validateField(readField, readFields);
            if (!readClearFields.add(readField)) {
                throw buildInvalid("clear 中存在重复字段: " + readField);
            }
        }
        for (String readField : readSet.keySet()) {
            validateField(readField, readFields);
            if (readClearFields.contains(readField)) {
                throw buildInvalid("字段不能同时出现在 set 和 clear 中: " + readField);
            }
        }

        BeanWrapper updateTarget = PropertyAccessorFactory.forBeanPropertyAccess(updateResource);
        Map<String, Object> readBeforeFields = new LinkedHashMap<>();
        Map<String, Object> updateFields = new LinkedHashMap<>();
        readSet.forEach((readField, readValue) -> {
            Object updateValue = parseValue(readField, readValue, readFields.get(readField));
            applyChange(updateTarget, readBeforeFields, updateFields, readField, updateValue);
        });
        readClearFields.forEach(readField ->
                applyChange(updateTarget, readBeforeFields, updateFields, readField, null));
        return new PatchValues(readBeforeFields, updateFields);
    }

    private void applyChange(
            BeanWrapper updateTarget,
            Map<String, Object> readBeforeFields,
            Map<String, Object> updateFields,
            String readField,
            Object updateValue) {
        Object readValue = updateTarget.getPropertyValue(readField);
        if (!Objects.equals(readValue, updateValue)) {
            readBeforeFields.put(readField, readValue);
            updateTarget.setPropertyValue(readField, updateValue);
            updateFields.put(readField, updateValue);
        }
    }

    private void recordChange(
            Long updateUserId,
            String readOrigin,
            ChangeResourceType readResourceType,
            boolean createResource,
            Long readVersion,
            PatchValues updatePatch,
            String readReason) {
        Long readExpectedVersion = createResource ? null : readVersion;
        Long updateVersion = createResource ? 0L : readVersion + 1;
        changeService.recordApplied(new UserChangeService.AppliedChange(
                updateUserId,
                readOrigin,
                readResourceType,
                updateUserId.toString(),
                createResource ? ChangeOperation.CREATE : ChangeOperation.PATCH,
                new ArrayList<>(updatePatch.afterFields().keySet()),
                readExpectedVersion,
                updateVersion,
                !createResource,
                true,
                updatePatch.beforeFields(),
                updatePatch.afterFields(),
                readReason));
    }

    private Object parseValue(String readField, Object readValue, FieldSpec readSpec) {
        if (readValue == null) {
            throw buildInvalid("set 不接受 null，请使用 clear 清空字段: " + readField);
        }
        if ("intentionCity".equals(readField)) {
            return parseCities(readValue);
        }
        if (readSpec.type() == String.class) {
            if (!(readValue instanceof String readText) || !StringUtils.hasText(readText)) {
                throw buildInvalid("字段 " + readField + " 必须是非空字符串；清空请使用 clear");
            }
            if (readText.length() > readSpec.maxLength()) {
                throw buildInvalid("字段 " + readField + " 最多允许 "
                        + readSpec.maxLength() + " 个字符");
            }
            return readText;
        }
        if (readSpec.type().isEnum()) {
            if (!(readValue instanceof String readName)) {
                throw buildInvalid("字段 " + readField + " 必须使用枚举名称");
            }
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object readEnum = Enum.valueOf((Class<? extends Enum>) readSpec.type(), readName);
                return readEnum;
            } catch (IllegalArgumentException readError) {
                throw buildInvalid("字段 " + readField + " 的枚举值无效: " + readName);
            }
        }
        if (readSpec.type() == LocalDate.class) {
            try {
                return LocalDate.parse(String.valueOf(readValue));
            } catch (RuntimeException readError) {
                throw buildInvalid("字段 " + readField + " 必须是 yyyy-MM-dd 日期");
            }
        }
        throw buildInvalid("字段 " + readField + " 的类型不受支持");
    }

    private String parseCities(Object readValue) {
        if (!(readValue instanceof List<?> readCities)) {
            throw buildInvalid("字段 intentionCity 必须是字符串数组");
        }
        if (readCities.size() > 20) {
            throw buildInvalid("字段 intentionCity 最多允许 20 个城市");
        }
        List<String> updateCities = new ArrayList<>(readCities.size());
        Set<String> readUniqueCities = new LinkedHashSet<>();
        for (Object readCity : readCities) {
            if (!(readCity instanceof String readText) || !StringUtils.hasText(readText)) {
                throw buildInvalid("字段 intentionCity 只能包含非空字符串");
            }
            String updateCity = readText.trim();
            if (updateCity.length() > 64) {
                throw buildInvalid("字段 intentionCity 的单个城市最多允许 64 个字符");
            }
            if (!readUniqueCities.add(updateCity)) {
                throw buildInvalid("字段 intentionCity 不能包含重复城市: " + updateCity);
            }
            updateCities.add(updateCity);
        }
        try {
            return objectMapper.writeValueAsString(updateCities);
        } catch (JsonProcessingException readError) {
            throw buildInvalid("字段 intentionCity 无法序列化");
        }
    }

    private void validateProfile(UserProfileEntity readProfile) {
        if (readProfile.getBirthDate() != null
                && readProfile.getBirthDate().isAfter(LocalDate.now())) {
            throw buildInvalid("birthDate 不能晚于今天");
        }
    }

    private void updateResource(
            String readResourceName,
            Long updateUserId,
            Long readVersion,
            Map<String, Object> updateFields,
            Map<String, FieldSpec> readFields,
            IService<?> updateService) {
        UpdateWrapper<Object> update = new UpdateWrapper<>();
        update.eq("user_id", updateUserId)
                .eq("version", readVersion)
                .set("version", readVersion + 1)
                .set("updated_at", LocalDateTime.now());
        updateFields.forEach((readField, readValue) ->
                update.set(readFields.get(readField).column(), readValue));
        @SuppressWarnings({"rawtypes", "unchecked"})
        boolean hasUpdated = ((IService) updateService).update(update);
        if (!hasUpdated) {
            throw buildConflict(readResourceName);
        }
    }

    private void saveNew(String readResourceName, SaveOperation saveOperation) {
        try {
            if (!saveOperation.saveResource()) {
                throw buildConflict(readResourceName);
            }
        } catch (DuplicateKeyException readError) {
            throw buildConflict(readResourceName);
        }
    }

    private void assertVersion(String readResourceName, Long readVersion, Long readCurrentVersion) {
        if (readVersion == null) {
            throw buildInvalid("expectedVersion 不能为空");
        }
        if (readCurrentVersion == null) {
            if (readVersion != 0L) {
                throw buildConflict(readResourceName);
            }
            return;
        }
        if (!Objects.equals(readVersion, readCurrentVersion)) {
            throw buildConflict(readResourceName);
        }
    }

    private void validateField(String readField, Map<String, FieldSpec> readFields) {
        if (!StringUtils.hasText(readField) || !readFields.containsKey(readField)) {
            throw buildInvalid("不允许修改字段: " + readField);
        }
    }

    private void requireAccount(Long readUserId) {
        if (readUserService.getUserById(readUserId) == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
    }

    private ServiceException buildInvalid(String readMessage) {
        return ServiceException.of(ResultCode.VALIDATE_FAILED, readMessage);
    }

    private ServiceException buildConflict(String readResourceName) {
        return ServiceException.of(ResultCode.CONFLICT,
                readResourceName + "已发生变化，请刷新后重试");
    }

    private static Map.Entry<String, FieldSpec> defineField(
            String readProperty,
            String readColumn,
            Class<?> readType) {
        return defineField(readProperty, readColumn, readType, 0);
    }

    private static Map.Entry<String, FieldSpec> defineField(
            String readProperty,
            String readColumn,
            Class<?> readType,
            int readMaxLength) {
        return Map.entry(readProperty, new FieldSpec(readColumn, readType, readMaxLength));
    }

    private record FieldSpec(String column, Class<?> type, int maxLength) {
    }

    private record PatchValues(
            Map<String, Object> beforeFields,
            Map<String, Object> afterFields) {
    }

    public record PreparedChange(
            ChangeResourceType resourceType,
            String resourceKey,
            ChangeOperation operation,
            List<String> changedFields,
            Long expectedVersion,
            boolean beforeExists,
            boolean afterExists,
            Map<String, Object> beforeFields,
            Map<String, Object> afterFields) {
    }

    @FunctionalInterface
    private interface SaveOperation {
        boolean saveResource();
    }
}
