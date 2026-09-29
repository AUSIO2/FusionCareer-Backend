package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.res.UserChangeActionPageResponse;
import com.fusioncareer.dto.res.UserChangeActionResponse;
import com.fusioncareer.entity.UserChangeActionEntity;
import com.fusioncareer.entity.UserChangeItemEntity;
import com.fusioncareer.enums.ChangeActionStatus;
import com.fusioncareer.enums.ChangeActionType;
import com.fusioncareer.enums.ChangeActorType;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.UserChangeActionMapper;
import com.fusioncareer.mapper.UserChangeItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个人空间不可变变更历史。
 */
@Service
@RequiredArgsConstructor
public class UserChangeService {

    private static final short SCHEMA_VERSION = 1;

    private final UserChangeActionMapper actionMapper;
    private final UserChangeItemMapper itemMapper;
    private final ChangeSnapshotCipher snapshotCipher;
    private final ObjectMapper objectMapper;

    @Transactional
    public Long recordApplied(AppliedChange readChange) {
        LocalDateTime createTime = LocalDateTime.now();
        UserChangeActionEntity createAction = new UserChangeActionEntity();
        createAction.setUserId(readChange.userId());
        createAction.setActorType(ChangeActorType.USER);
        createAction.setOrigin(readChange.origin());
        createAction.setActionType(ChangeActionType.APPLY);
        createAction.setStatus(ChangeActionStatus.APPLIED);
        createAction.setSchemaVersion(SCHEMA_VERSION);
        createAction.setReason(readChange.reason());
        createAction.setConfirmedAt(createTime);
        createAction.setAppliedAt(createTime);
        return saveAction(createAction, readChange);
    }

    @Transactional
    public UserChangeActionResponse recordPendingRevert(
            Long readActionId,
            AppliedChange readChange) {
        UserChangeActionEntity createAction = new UserChangeActionEntity();
        createAction.setUserId(readChange.userId());
        createAction.setActorType(ChangeActorType.USER);
        createAction.setOrigin(readChange.origin());
        createAction.setActionType(ChangeActionType.REVERT);
        createAction.setRevertsActionId(readActionId);
        createAction.setStatus(ChangeActionStatus.PENDING);
        createAction.setSchemaVersion(SCHEMA_VERSION);
        createAction.setReason(readChange.reason());
        Long createActionId = saveAction(createAction, readChange);
        return readAction(readChange.userId(), createActionId);
    }

    private Long saveAction(
            UserChangeActionEntity createAction,
            AppliedChange readChange) {
        if (actionMapper.insert(createAction) != 1) {
            throw buildFailure();
        }
        Map<String, Object> writeBefore = buildSnapshot(
                readChange.beforeExists(), readChange.beforeFields());
        Map<String, Object> writeAfter = buildSnapshot(
                readChange.afterExists(), readChange.afterFields());
        String readBeforeAad = buildAad(
                readChange.userId(), createAction.getId(), readChange.resourceType(),
                readChange.resourceKey(), "before");
        String readAfterAad = buildAad(
                readChange.userId(), createAction.getId(), readChange.resourceType(),
                readChange.resourceKey(), "after");
        ChangeSnapshotCipher.EncryptedSnapshot writeBeforeCipher =
                snapshotCipher.encryptSnapshot(writeBefore, readBeforeAad);
        ChangeSnapshotCipher.EncryptedSnapshot writeAfterCipher =
                snapshotCipher.encryptSnapshot(writeAfter, readAfterAad);

        UserChangeItemEntity createItem = new UserChangeItemEntity();
        createItem.setActionId(createAction.getId());
        createItem.setItemOrder((short) 0);
        createItem.setResourceType(readChange.resourceType());
        createItem.setResourceKey(readChange.resourceKey());
        createItem.setOperation(readChange.operation());
        createItem.setChangedFields(writeFields(readChange.changedFields()));
        createItem.setExpectedVersion(readChange.expectedVersion());
        createItem.setAppliedVersion(readChange.appliedVersion());
        createItem.setBeforeCiphertext(writeBeforeCipher.payload());
        createItem.setAfterCiphertext(writeAfterCipher.payload());
        createItem.setKeyVersion(writeBeforeCipher.keyVersion());
        if (itemMapper.insert(createItem) != 1) {
            throw buildFailure();
        }
        return createAction.getId();
    }

    @Transactional(readOnly = true)
    public UserChangeActionPageResponse readActions(
            Long readUserId,
            Long readBeforeId,
            int readSize) {
        int loadSize = Math.max(1, Math.min(readSize, 100));
        LambdaQueryWrapper<UserChangeActionEntity> readQuery =
                new LambdaQueryWrapper<UserChangeActionEntity>()
                        .eq(UserChangeActionEntity::getUserId, readUserId)
                        .orderByDesc(UserChangeActionEntity::getId)
                        .last("LIMIT " + (loadSize + 1));
        if (readBeforeId != null) {
            readQuery.lt(UserChangeActionEntity::getId, readBeforeId);
        }
        List<UserChangeActionEntity> readActions = actionMapper.selectList(readQuery);
        boolean hasMore = readActions.size() > loadSize;
        if (hasMore) {
            readActions = new ArrayList<>(readActions.subList(0, loadSize));
        }
        Map<Long, List<UserChangeItemEntity>> readItems = loadItems(readActions);
        List<UserChangeActionResponse> readResponses = readActions.stream()
                .map(readAction -> buildResponse(
                        readAction, readItems.getOrDefault(readAction.getId(), List.of())))
                .toList();
        Long readNextId = hasMore && !readActions.isEmpty()
                ? readActions.get(readActions.size() - 1).getId() : null;
        return new UserChangeActionPageResponse(readResponses, readNextId, hasMore);
    }

    @Transactional(readOnly = true)
    public UserChangeActionResponse readAction(Long readUserId, Long readActionId) {
        UserChangeActionEntity readAction = actionMapper.selectOne(
                new LambdaQueryWrapper<UserChangeActionEntity>()
                        .eq(UserChangeActionEntity::getId, readActionId)
                        .eq(UserChangeActionEntity::getUserId, readUserId));
        if (readAction == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "变更记录不存在");
        }
        List<UserChangeItemEntity> readItems = itemMapper.selectList(
                new LambdaQueryWrapper<UserChangeItemEntity>()
                        .eq(UserChangeItemEntity::getActionId, readActionId)
                        .orderByAsc(UserChangeItemEntity::getItemOrder));
        return buildResponse(readAction, readItems);
    }

    public String buildAad(
            Long readUserId,
            Long readActionId,
            ChangeResourceType readResourceType,
            String readResourceKey,
            String readSide) {
        return readUserId + "|" + readActionId + "|"
                + readResourceType + "|" + readResourceKey + "|" + readSide;
    }

    private Map<Long, List<UserChangeItemEntity>> loadItems(
            List<UserChangeActionEntity> readActions) {
        if (readActions.isEmpty()) {
            return Map.of();
        }
        List<Long> readActionIds = readActions.stream()
                .map(UserChangeActionEntity::getId)
                .toList();
        return itemMapper.selectList(
                        new LambdaQueryWrapper<UserChangeItemEntity>()
                                .in(UserChangeItemEntity::getActionId, readActionIds)
                                .orderByAsc(UserChangeItemEntity::getActionId)
                                .orderByAsc(UserChangeItemEntity::getItemOrder))
                .stream()
                .collect(Collectors.groupingBy(
                        UserChangeItemEntity::getActionId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private UserChangeActionResponse buildResponse(
            UserChangeActionEntity readAction,
            List<UserChangeItemEntity> readItems) {
        List<UserChangeActionResponse.Item> readItemResponses = readItems.stream()
                .map(readItem -> new UserChangeActionResponse.Item(
                        readItem.getId(),
                        readItem.getResourceType(),
                        readItem.getResourceKey(),
                        readItem.getOperation(),
                        parseFields(readItem.getChangedFields()),
                        readItem.getExpectedVersion(),
                        readItem.getAppliedVersion()))
                .toList();
        return new UserChangeActionResponse(
                readAction.getId(),
                readAction.getActorType(),
                readAction.getOrigin(),
                readAction.getActionType(),
                readAction.getRevertsActionId(),
                readAction.getStatus(),
                readAction.getReason(),
                readAction.getAppliedAt(),
                readAction.getCreatedAt(),
                readItemResponses);
    }

    private Map<String, Object> buildSnapshot(
            boolean readExists,
            Map<String, Object> readFields) {
        Map<String, Object> writeSnapshot = new LinkedHashMap<>();
        writeSnapshot.put("exists", readExists);
        writeSnapshot.put("fields", readFields);
        return writeSnapshot;
    }

    private String writeFields(List<String> writeFields) {
        try {
            return objectMapper.writeValueAsString(writeFields);
        } catch (JsonProcessingException readError) {
            throw buildFailure();
        }
    }

    private List<String> parseFields(String readFields) {
        try {
            return objectMapper.readValue(readFields, new TypeReference<>() { });
        } catch (JsonProcessingException readError) {
            throw buildFailure();
        }
    }

    private ServiceException buildFailure() {
        return ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                "个人空间变更历史写入失败");
    }

    public record AppliedChange(
            Long userId,
            String origin,
            ChangeResourceType resourceType,
            String resourceKey,
            ChangeOperation operation,
            List<String> changedFields,
            Long expectedVersion,
            Long appliedVersion,
            boolean beforeExists,
            boolean afterExists,
            Map<String, Object> beforeFields,
            Map<String, Object> afterFields,
            String reason) {
    }
}
