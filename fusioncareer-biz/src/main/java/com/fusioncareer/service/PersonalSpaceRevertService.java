package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.UserChangeActionResponse;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.UserChangeActionEntity;
import com.fusioncareer.entity.UserChangeItemEntity;
import com.fusioncareer.entity.UserMemoryEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeActionType;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.UserChangeActionMapper;
import com.fusioncareer.mapper.UserChangeItemMapper;
import com.fusioncareer.mapper.UserMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Profile、Resume 与 Memory 的 Git-like 三方回退。
 */
@Service
@RequiredArgsConstructor
public class PersonalSpaceRevertService {

    private final UserChangeActionMapper actionMapper;
    private final UserChangeItemMapper itemMapper;
    private final ChangeSnapshotCipher snapshotCipher;
    private final UserChangeService changeService;
    private final PersonalSpaceMutationService mutationService;
    private final UserMemoryService memoryService;
    private final UserProfileService profileService;
    private final ResumeService resumeService;
    private final ResumeFileService fileService;
    private final QuestionnaireAnswerService answerService;
    private final UserMemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public UserChangeActionResponse createRevert(Long readUserId, Long readActionId) {
        UserChangeActionEntity readPending = actionMapper.selectOne(
                new LambdaQueryWrapper<UserChangeActionEntity>()
                        .eq(UserChangeActionEntity::getUserId, readUserId)
                        .eq(UserChangeActionEntity::getRevertsActionId, readActionId)
                        .eq(UserChangeActionEntity::getStatus, ChangeActionStatus.PENDING)
                        .orderByDesc(UserChangeActionEntity::getId)
                        .last("LIMIT 1"));
        if (readPending != null) {
            return changeService.readAction(readUserId, readPending.getId());
        }

        UserChangeActionEntity readAction = loadApplied(readUserId, readActionId);
        List<UserChangeItemEntity> readItems = loadItems(readActionId, false);
        if (readItems.size() == 1) {
            UserChangeItemEntity readItem = readItems.get(0);
            List<String> readFields = parseFields(readItem.getChangedFields());
            Snapshot readAfter = decryptSnapshot(
                    readUserId, readActionId, readItem, "after");
            ResourceState readCurrent = loadState(
                    readUserId, readItem.getResourceType(),
                    readItem.getResourceKey(), readFields);
            UserChangeActionResponse readWithdrawal = createReviewedWithdrawal(
                    readUserId, readAction, readItem, readAfter, readCurrent);
            if (readWithdrawal != null) {
                return readWithdrawal;
            }
        }

        List<UserChangeService.AppliedChange> createChanges = new ArrayList<>();
        for (UserChangeItemEntity readItem : readItems) {
            createChanges.add(prepareRevertChange(
                    readUserId, readActionId, readItem));
        }
        return changeService.recordPendingRevert(readActionId, createChanges);
    }

    private UserChangeService.AppliedChange prepareRevertChange(
            Long readUserId,
            Long readActionId,
            UserChangeItemEntity readItem) {
        List<String> readFields = parseFields(readItem.getChangedFields());
        Snapshot readBefore = decryptSnapshot(readUserId, readActionId, readItem, "before");
        Snapshot readAfter = decryptSnapshot(readUserId, readActionId, readItem, "after");
        ResourceState readCurrent = loadState(
                readUserId, readItem.getResourceType(), readItem.getResourceKey(), readFields);
        Map<String, Object> readCurrentFields = new LinkedHashMap<>();
        Map<String, Object> updateFields = new LinkedHashMap<>();
        if (readBefore.exists() != readAfter.exists()) {
            if (matchesState(readCurrent, readBefore, readFields)) {
                throw buildConflict("该操作已经回退，无需重复处理");
            }
            if (!matchesState(readCurrent, readAfter, readFields)) {
                throw buildConflict("资源后来已发生变化，不能自动回退");
            }
            for (String readField : readFields) {
                readCurrentFields.put(readField, readCurrent.fields().get(readField));
                updateFields.put(readField, readBefore.fields().get(readField));
            }
        } else {
            if (readCurrent.exists() != readAfter.exists()) {
                throw buildConflict("资源状态已经变化，不能自动回退");
            }
            for (String readField : readFields) {
                Object readValue = readCurrent.fields().get(readField);
                Object readAfterValue = readAfter.fields().get(readField);
                Object readBeforeValue = readBefore.fields().get(readField);
                if (sameValue(readValue, readAfterValue)) {
                    readCurrentFields.put(readField, readValue);
                    updateFields.put(readField, readBeforeValue);
                } else if (!sameValue(readValue, readBeforeValue)) {
                    throw buildConflict("字段 " + readField + " 后来已被修改，不能自动回退");
                }
            }
            if (updateFields.isEmpty()) {
                throw buildConflict("该操作已经回退，无需重复处理");
            }
        }

        ChangeOperation createOperation = resolveOperation(
                readItem.getResourceType(),
                readCurrent.exists(),
                readBefore.exists(),
                readBefore.fields());
        return new UserChangeService.AppliedChange(
                readUserId,
                "HISTORY_UI",
                readItem.getResourceType(),
                readItem.getResourceKey(),
                createOperation,
                new ArrayList<>(updateFields.keySet()),
                readCurrent.version(),
                null,
                readCurrent.exists(),
                readBefore.exists(),
                readCurrentFields,
                updateFields,
                "回退操作 " + readActionId);
    }

    @Transactional
    public UserChangeActionResponse confirmAction(Long readUserId, Long readActionId) {
        UserChangeActionEntity updateAction = loadAction(readUserId, readActionId, true);
        if (updateAction.getStatus() == ChangeActionStatus.APPLIED) {
            return changeService.readAction(readUserId, readActionId);
        }
        if (updateAction.getStatus() != ChangeActionStatus.PENDING) {
            throw buildConflict("该操作当前不可确认");
        }

        List<PendingApplication> updateApplications = new ArrayList<>();
        for (UserChangeItemEntity updateItem : loadItems(readActionId, true)) {
            List<String> readFields = parseFields(updateItem.getChangedFields());
            Snapshot readBefore = decryptSnapshot(
                    readUserId, readActionId, updateItem, "before");
            Snapshot readAfter = decryptSnapshot(
                    readUserId, readActionId, updateItem, "after");
            ResourceState readCurrent = loadState(
                    readUserId, updateItem.getResourceType(),
                    updateItem.getResourceKey(), readFields);
            if (!Objects.equals(readCurrent.version(), updateItem.getExpectedVersion())
                    || !matchesState(readCurrent, readBefore, readFields)) {
                throw buildConflict("资源在确认前已发生变化，请重新生成提案");
            }
            updateApplications.add(new PendingApplication(
                    updateItem, readCurrent, readAfter));
        }

        for (PendingApplication updateApplication : updateApplications) {
            UserChangeItemEntity updateItem = updateApplication.item();
            Long updateVersion = applySnapshot(
                    readUserId, updateItem.getResourceType(), updateItem.getResourceKey(),
                    updateApplication.current().version(),
                    updateApplication.after().exists(),
                    updateApplication.after().fields());
            updateItem.setAppliedVersion(updateVersion);
            if (itemMapper.updateById(updateItem) != 1) {
                throw buildConflict("操作项状态更新失败");
            }
        }
        LocalDateTime updateTime = LocalDateTime.now();
        updateAction.setStatus(ChangeActionStatus.APPLIED);
        updateAction.setConfirmedAt(updateTime);
        updateAction.setAppliedAt(updateTime);
        updateAction.setUpdatedAt(updateTime);
        if (actionMapper.updateById(updateAction) != 1) {
            throw buildConflict("操作状态更新失败");
        }
        return changeService.readAction(readUserId, readActionId);
    }

    @Transactional
    public UserChangeActionResponse rejectAction(Long readUserId, Long readActionId) {
        UserChangeActionEntity updateAction = loadAction(readUserId, readActionId, true);
        if (updateAction.getStatus() == ChangeActionStatus.REJECTED) {
            return changeService.readAction(readUserId, readActionId);
        }
        if (updateAction.getStatus() != ChangeActionStatus.PENDING) {
            throw buildConflict("该操作当前不可拒绝");
        }
        updateAction.setStatus(ChangeActionStatus.REJECTED);
        updateAction.setUpdatedAt(LocalDateTime.now());
        if (actionMapper.updateById(updateAction) != 1) {
            throw buildConflict("操作状态更新失败");
        }
        return changeService.readAction(readUserId, readActionId);
    }

    private UserChangeActionResponse createReviewedWithdrawal(
            Long readUserId,
            UserChangeActionEntity readAction,
            UserChangeItemEntity readItem,
            Snapshot readAfter,
            ResourceState readCurrent) {
        boolean readWasSubmit = isSubmissionStatus(
                readAfter.fields().get("submissionStatus"),
                QuestionnaireSubmissionStatus.SUBMITTED)
                || "propose_questionnaire_submit".equals(readAction.getToolName())
                || ("QUESTIONNAIRE_UI".equals(readAction.getOrigin())
                && "提交问卷作答".equals(readAction.getReason()));
        boolean readAnswersMatch = !readAfter.fields().containsKey("answers")
                || sameValue(
                        readCurrent.fields().get("answers"),
                        readAfter.fields().get("answers"));
        if (readItem.getResourceType() != ChangeResourceType.QUESTIONNAIRE_ANSWER
                || !readCurrent.exists()
                || !readWasSubmit
                || !isSubmissionStatus(
                        readCurrent.fields().get("submissionStatus"),
                        QuestionnaireSubmissionStatus.REVIEWED)
                || !readAnswersMatch) {
            return null;
        }
        Map<String, Object> readBeforeFields = Map.of(
                "submissionStatus", QuestionnaireSubmissionStatus.REVIEWED);
        Map<String, Object> updateFields = Map.of(
                "submissionStatus", QuestionnaireSubmissionStatus.WITHDRAWN);
        UserChangeService.AppliedChange createWithdrawal = new UserChangeService.AppliedChange(
                readUserId,
                "HISTORY_UI",
                ChangeResourceType.QUESTIONNAIRE_ANSWER,
                readItem.getResourceKey(),
                ChangeOperation.STATE_TRANSITION,
                List.of("submissionStatus"),
                readCurrent.version(),
                null,
                true,
                true,
                readBeforeFields,
                updateFields,
                "撤回已审核投递 " + readItem.getResourceKey());
        return changeService.recordPendingRevert(readAction.getId(), createWithdrawal);
    }

    private boolean isSubmissionStatus(
            Object readValue,
            QuestionnaireSubmissionStatus readStatus) {
        return readValue == readStatus
                || readStatus.name().equals(String.valueOf(readValue));
    }

    private Long applySnapshot(
            Long updateUserId,
            ChangeResourceType readResourceType,
            String readResourceKey,
            Long readVersion,
            boolean updateExists,
            Map<String, Object> updateFields) {
        if (readResourceType == ChangeResourceType.MEMORY) {
            return memoryService.restoreSnapshot(
                    updateUserId, readVersion, updateExists, updateFields);
        }
        if (readResourceType == ChangeResourceType.RESUME_FILE) {
            if (updateFields.size() != 1 || !updateFields.containsKey("deleted")) {
                throw buildConflict("文件变更快照格式错误");
            }
            Object readDeleted = updateFields.get("deleted");
            if ((updateExists && !Boolean.FALSE.equals(readDeleted))
                    || (!updateExists && readDeleted != null)) {
                throw buildConflict("文件变更快照格式错误");
            }
            return fileService.restoreSnapshot(
                    updateUserId,
                    parseResourceId(readResourceKey),
                    readVersion,
                    !updateExists);
        }
        if (readResourceType == ChangeResourceType.QUESTIONNAIRE_ANSWER) {
            return answerService.restoreSnapshot(
                    updateUserId,
                    parseResourceId(readResourceKey),
                    readVersion,
                    updateExists,
                    updateFields);
        }
        return mutationService.restoreSnapshot(
                updateUserId, readResourceType, readVersion, updateExists, updateFields);
    }

    private UserChangeActionEntity loadApplied(Long readUserId, Long readActionId) {
        UserChangeActionEntity readAction = loadAction(readUserId, readActionId, false);
        if (readAction.getStatus() != ChangeActionStatus.APPLIED) {
            throw buildConflict("只有已应用的操作可以回退");
        }
        return readAction;
    }

    private UserChangeActionEntity loadAction(
            Long readUserId,
            Long readActionId,
            boolean lockAction) {
        LambdaQueryWrapper<UserChangeActionEntity> readQuery =
                new LambdaQueryWrapper<UserChangeActionEntity>()
                        .eq(UserChangeActionEntity::getId, readActionId)
                        .eq(UserChangeActionEntity::getUserId, readUserId);
        if (lockAction) {
            readQuery.last("FOR UPDATE");
        }
        UserChangeActionEntity readAction = actionMapper.selectOne(readQuery);
        if (readAction == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "变更记录不存在");
        }
        return readAction;
    }

    private List<UserChangeItemEntity> loadItems(Long readActionId, boolean lockItems) {
        LambdaQueryWrapper<UserChangeItemEntity> readQuery =
                new LambdaQueryWrapper<UserChangeItemEntity>()
                        .eq(UserChangeItemEntity::getActionId, readActionId)
                        .orderByAsc(UserChangeItemEntity::getItemOrder);
        if (lockItems) {
            readQuery.last("FOR UPDATE");
        }
        List<UserChangeItemEntity> readItems = itemMapper.selectList(readQuery);
        if (readItems.isEmpty() || readItems.size() > 16) {
            throw buildConflict("操作项数量无效");
        }
        return readItems;
    }

    private Snapshot decryptSnapshot(
            Long readUserId,
            Long readActionId,
            UserChangeItemEntity readItem,
            String readSide) {
        byte[] readCiphertext = "before".equals(readSide)
                ? readItem.getBeforeCiphertext() : readItem.getAfterCiphertext();
        String readAad = changeService.buildAad(
                readUserId, readActionId, readItem.getResourceType(),
                readItem.getResourceKey(), readSide);
        Map<String, Object> readSnapshot = snapshotCipher.decryptSnapshot(
                readCiphertext, readItem.getKeyVersion(), readAad);
        boolean readExists = Boolean.TRUE.equals(readSnapshot.get("exists"));
        Object readFields = readSnapshot.get("fields");
        if (!(readFields instanceof Map<?, ?> readFieldMap)) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "变更快照格式错误");
        }
        Map<String, Object> updateFields = new LinkedHashMap<>();
        readFieldMap.forEach((readKey, readValue) ->
                updateFields.put(String.valueOf(readKey), readValue));
        return new Snapshot(readExists, updateFields);
    }

    private ResourceState loadState(
            Long readUserId,
            ChangeResourceType readResourceType,
            String readResourceKey,
            List<String> readFields) {
        if (readResourceType == ChangeResourceType.PROFILE) {
            UserProfileEntity readProfile = profileService.getById(readUserId);
            return readBean(readProfile, readProfile == null ? null : readProfile.getVersion(), readFields);
        }
        if (readResourceType == ChangeResourceType.RESUME) {
            ResumeEntity readResume = resumeService.getById(readUserId);
            return readBean(readResume, readResume == null ? null : readResume.getVersion(), readFields);
        }
        if (readResourceType == ChangeResourceType.MEMORY) {
            return readMemory(readUserId, readFields);
        }
        if (readResourceType == ChangeResourceType.RESUME_FILE) {
            if (!readFields.equals(List.of("deleted"))) {
                throw buildConflict("文件变更字段格式错误");
            }
            ResumeFileEntity readFile = fileService.getOwnFileIncludingDeleted(
                    readUserId, parseResourceId(readResourceKey));
            boolean readExists = readFile.getDeletedAt() == null;
            Map<String, Object> readValues = new LinkedHashMap<>();
            readValues.put("deleted", readExists ? false : null);
            return new ResourceState(
                    readExists,
                    readFile.getVersion(),
                    readValues);
        }
        if (readResourceType == ChangeResourceType.QUESTIONNAIRE_ANSWER) {
            QuestionnaireAnswerEntity readAnswer = answerService.getOwnAnswerIncludingDeleted(
                    readUserId, parseResourceId(readResourceKey));
            if (readAnswer == null) {
                Map<String, Object> readValues = new LinkedHashMap<>();
                readFields.forEach(readField -> readValues.put(readField, null));
                return new ResourceState(false, null, readValues);
            }
            if (readAnswer.getDeletedAt() != null) {
                Map<String, Object> readValues = new LinkedHashMap<>();
                readFields.forEach(readField -> readValues.put(readField, null));
                return new ResourceState(false, readAnswer.getVersion(), readValues);
            }
            return readBean(readAnswer, readAnswer.getVersion(), readFields);
        }
        throw buildConflict("该资源类型暂不支持自动回退");
    }

    private Long parseResourceId(String readResourceKey) {
        try {
            return Long.valueOf(readResourceKey);
        } catch (NumberFormatException readError) {
            throw buildConflict("资源键格式错误");
        }
    }

    private ResourceState readBean(Object readResource, Long readVersion, List<String> readFields) {
        Map<String, Object> readValues = new LinkedHashMap<>();
        if (readResource == null) {
            readFields.forEach(readField -> readValues.put(readField, null));
            return new ResourceState(false, null, readValues);
        }
        BeanWrapper readTarget = PropertyAccessorFactory.forBeanPropertyAccess(readResource);
        readFields.forEach(readField ->
                readValues.put(readField, readTarget.getPropertyValue(readField)));
        return new ResourceState(true, readVersion, readValues);
    }

    private ResourceState readMemory(Long readUserId, List<String> readFields) {
        UserMemoryEntity readMemory = memoryMapper.selectById(readUserId);
        Map<String, Object> readEntries = new LinkedHashMap<>();
        if (readMemory != null) {
            try {
                readEntries.putAll(objectMapper.readValue(
                        readMemory.getMemoryJson(), new TypeReference<>() { }));
            } catch (JsonProcessingException readError) {
                throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "用户记忆格式错误");
            }
        }
        Map<String, Object> readValues = new LinkedHashMap<>();
        readFields.forEach(readField -> readValues.put(readField, readEntries.get(readField)));
        return new ResourceState(
                readMemory != null,
                readMemory == null ? null : readMemory.getVersion(),
                readValues);
    }

    private boolean matchesState(
            ResourceState readState,
            Snapshot readSnapshot,
            List<String> readFields) {
        if (readState.exists() != readSnapshot.exists()) {
            return false;
        }
        return readFields.stream().allMatch(readField -> sameValue(
                readState.fields().get(readField), readSnapshot.fields().get(readField)));
    }

    private boolean sameValue(Object readLeft, Object readRight) {
        if (readLeft instanceof String readLeftText
                && readRight instanceof String readRightText) {
            try {
                JsonNode readLeftJson = objectMapper.readTree(readLeftText);
                JsonNode readRightJson = objectMapper.readTree(readRightText);
                if (readLeftJson.isContainerNode() && readRightJson.isContainerNode()) {
                    return readLeftJson.equals(readRightJson);
                }
            } catch (JsonProcessingException ignored) {
                // 普通文本继续按 JSON tree 的字符串表示比较。
            }
        }
        return objectMapper.valueToTree(readLeft).equals(objectMapper.valueToTree(readRight));
    }

    private List<String> parseFields(String readFields) {
        try {
            return objectMapper.readValue(readFields, new TypeReference<>() { });
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "变更字段格式错误");
        }
    }

    private ChangeOperation resolveOperation(
            ChangeResourceType readResourceType,
            boolean readExists,
            boolean updateExists,
            Map<String, Object> updateFields) {
        if (readResourceType == ChangeResourceType.RESUME_FILE) {
            return updateExists ? ChangeOperation.RESTORE : ChangeOperation.SOFT_DELETE;
        }
        if (!readExists && updateExists) {
            return ChangeOperation.CREATE;
        }
        if (readExists && !updateExists) {
            return ChangeOperation.DELETE;
        }
        if (readResourceType == ChangeResourceType.QUESTIONNAIRE_ANSWER
                && updateFields.containsKey("submissionStatus")) {
            return ChangeOperation.STATE_TRANSITION;
        }
        return ChangeOperation.PATCH;
    }

    private ServiceException buildConflict(String readMessage) {
        return ServiceException.of(ResultCode.CONFLICT, readMessage);
    }

    private record Snapshot(boolean exists, Map<String, Object> fields) {
    }

    private record ResourceState(boolean exists, Long version, Map<String, Object> fields) {
    }

    private record PendingApplication(
            UserChangeItemEntity item,
            ResourceState current,
            Snapshot after) {
    }
}
