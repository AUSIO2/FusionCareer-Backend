package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.req.UserMemoryUpdateRequest;
import com.fusioncareer.dto.res.UserMemoryResponse;
import com.fusioncareer.entity.UserMemoryEntity;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.UserMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 小型、白名单、版本化的用户长期记忆。
 */
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private static final int MAX_MEMORY_BYTES = 4 * 1024;
    private static final int MAX_VALUE_LENGTH = 256;
    private static final int MAX_LIST_SIZE = 10;
    private static final Map<String, MemoryType> MEMORY_TYPES = Map.of(
            "responseStyle", MemoryType.TEXT,
            "currentGoal", MemoryType.TEXT,
            "targetCities", MemoryType.TEXT_LIST,
            "targetIndustries", MemoryType.TEXT_LIST,
            "preferredWorkModes", MemoryType.TEXT_LIST,
            "temporaryConstraints", MemoryType.TEXT_LIST
    );

    private final UserMemoryMapper memoryMapper;
    private final UserChangeService changeService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public UserMemoryResponse readMemory(Long readUserId) {
        return buildResponse(memoryMapper.selectById(readUserId));
    }

    @Transactional
    public UserMemoryResponse saveMemory(
            Long updateUserId,
            String updateKey,
            UserMemoryUpdateRequest readRequest) {
        validateKey(updateKey);
        UserMemoryEntity readMemory = memoryMapper.selectById(updateUserId);
        validateVersion(readRequest.expectedVersion(), readMemory);

        Map<String, UserMemoryResponse.Entry> updateEntries = parseEntries(readMemory);
        Object updateValue = normalizeValue(updateKey, readRequest.value());
        UserMemoryResponse.Entry readEntry = updateEntries.get(updateKey);
        if (readEntry != null && Objects.equals(readEntry.value(), updateValue)) {
            return buildResponse(readMemory, updateEntries);
        }
        UserMemoryResponse.Entry updateEntry = new UserMemoryResponse.Entry(
                updateValue, null, LocalDateTime.now());
        updateEntries.put(updateKey, updateEntry);
        String updateJson = writeEntries(updateEntries);
        saveMemory(updateUserId, readRequest.expectedVersion(), readMemory, updateJson);
        Map<String, Object> readBeforeFields = new LinkedHashMap<>();
        readBeforeFields.put(updateKey, readEntry);
        Map<String, Object> updateFields = new LinkedHashMap<>();
        updateFields.put(updateKey, updateEntry);
        recordChange(
                updateUserId,
                readMemory == null ? ChangeOperation.CREATE : ChangeOperation.PATCH,
                readMemory == null ? null : readRequest.expectedVersion(),
                readMemory == null ? 0L : readRequest.expectedVersion() + 1,
                readMemory != null,
                readBeforeFields,
                updateFields,
                "设置长期记忆 " + updateKey);
        return readMemory(updateUserId);
    }

    @Transactional
    public UserMemoryResponse deleteMemory(
            Long updateUserId,
            String deleteKey,
            Long readVersion) {
        validateKey(deleteKey);
        UserMemoryEntity readMemory = memoryMapper.selectById(updateUserId);
        validateVersion(readVersion, readMemory);
        if (readMemory == null) {
            return buildResponse(null);
        }

        Map<String, UserMemoryResponse.Entry> updateEntries = parseEntries(readMemory);
        UserMemoryResponse.Entry readEntry = updateEntries.remove(deleteKey);
        if (readEntry == null) {
            return buildResponse(readMemory, updateEntries);
        }
        updateMemory(updateUserId, readVersion, writeEntries(updateEntries));
        Map<String, Object> readBeforeFields = new LinkedHashMap<>();
        readBeforeFields.put(deleteKey, readEntry);
        Map<String, Object> updateFields = new LinkedHashMap<>();
        updateFields.put(deleteKey, null);
        recordChange(
                updateUserId,
                ChangeOperation.PATCH,
                readVersion,
                readVersion + 1,
                true,
                readBeforeFields,
                updateFields,
                "删除长期记忆 " + deleteKey);
        return readMemory(updateUserId);
    }

    @Transactional
    public UserMemoryResponse clearMemory(Long updateUserId, Long readVersion) {
        UserMemoryEntity readMemory = memoryMapper.selectById(updateUserId);
        validateVersion(readVersion, readMemory);
        if (readMemory == null) {
            return buildResponse(null);
        }
        Map<String, UserMemoryResponse.Entry> readEntries = parseEntries(readMemory);
        if (readEntries.isEmpty()) {
            return buildResponse(readMemory, readEntries);
        }
        updateMemory(updateUserId, readVersion, "{}");
        Map<String, Object> readBeforeFields = new LinkedHashMap<>(readEntries);
        Map<String, Object> updateFields = new LinkedHashMap<>();
        readEntries.keySet().forEach(readKey -> updateFields.put(readKey, null));
        recordChange(
                updateUserId,
                ChangeOperation.PATCH,
                readVersion,
                readVersion + 1,
                true,
                readBeforeFields,
                updateFields,
                "清空长期记忆");
        return readMemory(updateUserId);
    }

    /**
     * 校验并计算 Agent 长期记忆提案，但不写入业务表。
     */
    @Transactional(readOnly = true)
    public PreparedChange prepareProposal(
            Long readUserId,
            Map<String, Object> readSet,
            List<String> readClear) {
        UserMemoryEntity readMemory = memoryMapper.selectById(readUserId);
        Map<String, UserMemoryResponse.Entry> updateEntries = parseEntries(readMemory);
        Map<String, Object> readBeforeFields = new LinkedHashMap<>();
        Map<String, Object> updateFields = new LinkedHashMap<>();

        Map<String, Object> createSet = readSet == null ? Map.of() : readSet;
        List<String> createClear = readClear == null ? List.of() : readClear;
        if (createSet.isEmpty() && createClear.isEmpty()) {
            throw buildInvalid("set 和 clear 至少需要提供一个记忆项");
        }
        Set<String> readClearKeys = new LinkedHashSet<>();
        for (String readKey : createClear) {
            validateKey(readKey);
            if (!readClearKeys.add(readKey)) {
                throw buildInvalid("clear 中存在重复记忆项: " + readKey);
            }
        }
        for (Map.Entry<String, Object> readChange : createSet.entrySet()) {
            String readKey = readChange.getKey();
            validateKey(readKey);
            if (readClearKeys.contains(readKey)) {
                throw buildInvalid("记忆项不能同时出现在 set 和 clear 中: " + readKey);
            }
            Object updateValue = normalizeValue(readKey, readChange.getValue());
            UserMemoryResponse.Entry readEntry = updateEntries.get(readKey);
            if (readEntry != null && Objects.equals(readEntry.value(), updateValue)) {
                continue;
            }
            UserMemoryResponse.Entry updateEntry = new UserMemoryResponse.Entry(
                    updateValue, null, LocalDateTime.now());
            readBeforeFields.put(readKey, readEntry);
            updateFields.put(readKey, updateEntry);
            updateEntries.put(readKey, updateEntry);
        }
        for (String readKey : readClearKeys) {
            UserMemoryResponse.Entry readEntry = updateEntries.remove(readKey);
            if (readEntry == null) {
                continue;
            }
            readBeforeFields.put(readKey, readEntry);
            updateFields.put(readKey, null);
        }
        if (updateFields.isEmpty()) {
            throw buildInvalid("提案没有产生实际变化");
        }
        writeEntries(updateEntries);
        return new PreparedChange(
                ChangeResourceType.MEMORY,
                readUserId.toString(),
                readMemory == null ? ChangeOperation.CREATE : ChangeOperation.PATCH,
                new ArrayList<>(updateFields.keySet()),
                readMemory == null ? null : readMemory.getVersion(),
                readMemory != null,
                true,
                readBeforeFields,
                updateFields);
    }

    @Transactional
    public Long restoreSnapshot(
            Long updateUserId,
            Long readVersion,
            boolean updateExists,
            Map<String, Object> updateFields) {
        UserMemoryEntity readMemory = memoryMapper.selectById(updateUserId);
        if (!updateExists) {
            if (readMemory == null || readVersion == null
                    || memoryMapper.delete(new LambdaQueryWrapper<UserMemoryEntity>()
                    .eq(UserMemoryEntity::getUserId, updateUserId)
                    .eq(UserMemoryEntity::getVersion, readVersion)) != 1) {
                throw buildConflict();
            }
            return null;
        }

        if (readMemory == null && readVersion != null) {
            throw buildConflict();
        }
        if (readMemory != null) {
            validateVersion(readVersion, readMemory);
        }
        Map<String, UserMemoryResponse.Entry> updateEntries = parseEntries(readMemory);
        updateFields.forEach((readKey, readValue) -> {
            validateKey(readKey);
            if (readValue == null) {
                updateEntries.remove(readKey);
                return;
            }
            UserMemoryResponse.Entry updateEntry = readValue instanceof UserMemoryResponse.Entry readEntry
                    ? readEntry
                    : objectMapper.convertValue(readValue, UserMemoryResponse.Entry.class);
            updateEntries.put(readKey, updateEntry);
        });
        String updateJson = writeEntries(updateEntries);
        if (readMemory == null) {
            saveMemory(updateUserId, 0L, null, updateJson);
            return 0L;
        }
        updateMemory(updateUserId, readVersion, updateJson);
        return readVersion + 1;
    }

    private void recordChange(
            Long updateUserId,
            ChangeOperation readOperation,
            Long readVersion,
            Long updateVersion,
            boolean readExists,
            Map<String, Object> readBeforeFields,
            Map<String, Object> updateFields,
            String readReason) {
        changeService.recordApplied(new UserChangeService.AppliedChange(
                updateUserId,
                "MEMORY_UI",
                ChangeResourceType.MEMORY,
                updateUserId.toString(),
                readOperation,
                new ArrayList<>(updateFields.keySet()),
                readVersion,
                updateVersion,
                readExists,
                true,
                readBeforeFields,
                updateFields,
                readReason));
    }

    private void saveMemory(
            Long updateUserId,
            Long readVersion,
            UserMemoryEntity readMemory,
            String updateJson) {
        if (readMemory != null) {
            updateMemory(updateUserId, readVersion, updateJson);
            return;
        }
        UserMemoryEntity createMemory = new UserMemoryEntity();
        createMemory.setUserId(updateUserId);
        createMemory.setMemoryJson(updateJson);
        createMemory.setVersion(0L);
        try {
            if (memoryMapper.insert(createMemory) != 1) {
                throw buildConflict();
            }
        } catch (DuplicateKeyException readError) {
            throw buildConflict();
        }
    }

    private void updateMemory(Long updateUserId, Long readVersion, String updateJson) {
        UpdateWrapper<UserMemoryEntity> updateMemory = new UpdateWrapper<>();
        updateMemory.eq("user_id", updateUserId)
                .eq("version", readVersion)
                .set("memory_json", updateJson)
                .set("version", readVersion + 1)
                .set("updated_at", LocalDateTime.now());
        if (memoryMapper.update(null, updateMemory) != 1) {
            throw buildConflict();
        }
    }

    private Map<String, UserMemoryResponse.Entry> parseEntries(UserMemoryEntity readMemory) {
        if (readMemory == null || !StringUtils.hasText(readMemory.getMemoryJson())) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(readMemory.getMemoryJson(), new TypeReference<>() { });
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "用户记忆数据格式错误");
        }
    }

    private String writeEntries(Map<String, UserMemoryResponse.Entry> writeEntries) {
        try {
            String writeJson = objectMapper.writeValueAsString(writeEntries);
            if (writeJson.getBytes(StandardCharsets.UTF_8).length > MAX_MEMORY_BYTES) {
                throw buildInvalid("长期记忆最多允许 4KB");
            }
            return writeJson;
        } catch (JsonProcessingException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "用户记忆无法序列化");
        }
    }

    private Object normalizeValue(String readKey, Object readValue) {
        MemoryType readType = MEMORY_TYPES.get(readKey);
        if (readType == MemoryType.TEXT) {
            return normalizeText(readKey, readValue);
        }
        if (!(readValue instanceof List<?> readValues)) {
            throw buildInvalid("记忆项 " + readKey + " 必须是字符串数组");
        }
        if (readValues.isEmpty() || readValues.size() > MAX_LIST_SIZE) {
            throw buildInvalid("记忆项 " + readKey + " 必须包含 1 到 10 项");
        }
        List<String> updateValues = new ArrayList<>(readValues.size());
        Set<String> readUniqueValues = new LinkedHashSet<>();
        for (Object readValueItem : readValues) {
            String updateValue = normalizeText(readKey, readValueItem);
            if (!readUniqueValues.add(updateValue)) {
                throw buildInvalid("记忆项 " + readKey + " 不能包含重复值");
            }
            updateValues.add(updateValue);
        }
        return updateValues;
    }

    private String normalizeText(String readKey, Object readValue) {
        if (!(readValue instanceof String readText) || !StringUtils.hasText(readText)) {
            throw buildInvalid("记忆项 " + readKey + " 必须是非空字符串");
        }
        String updateText = readText.trim();
        if (updateText.length() > MAX_VALUE_LENGTH) {
            throw buildInvalid("记忆项 " + readKey + " 最多允许 256 个字符");
        }
        return updateText;
    }

    private void validateVersion(Long readVersion, UserMemoryEntity readMemory) {
        if (readVersion == null || readVersion < 0) {
            throw buildInvalid("expectedVersion 不能为空且不能小于 0");
        }
        Long readCurrentVersion = readMemory == null ? 0L : readMemory.getVersion();
        if (!Objects.equals(readVersion, readCurrentVersion)) {
            throw buildConflict();
        }
    }

    private void validateKey(String readKey) {
        if (!MEMORY_TYPES.containsKey(readKey)) {
            throw buildInvalid("不允许使用记忆 key: " + readKey);
        }
    }

    private UserMemoryResponse buildResponse(UserMemoryEntity readMemory) {
        return buildResponse(readMemory, parseEntries(readMemory));
    }

    private UserMemoryResponse buildResponse(
            UserMemoryEntity readMemory,
            Map<String, UserMemoryResponse.Entry> readEntries) {
        if (readMemory == null) {
            return new UserMemoryResponse(0L, Map.copyOf(readEntries), null, null);
        }
        return new UserMemoryResponse(
                readMemory.getVersion(),
                Map.copyOf(readEntries),
                readMemory.getCreatedAt(),
                readMemory.getUpdatedAt());
    }

    private ServiceException buildInvalid(String readMessage) {
        return ServiceException.of(ResultCode.VALIDATE_FAILED, readMessage);
    }

    private ServiceException buildConflict() {
        return ServiceException.of(ResultCode.CONFLICT,
                "长期记忆已发生变化，请刷新后重试");
    }

    private enum MemoryType {
        TEXT,
        TEXT_LIST
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
}
