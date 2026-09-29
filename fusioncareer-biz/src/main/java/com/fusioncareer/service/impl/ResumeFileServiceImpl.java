package com.fusioncareer.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.enums.ChangeOperation;
import com.fusioncareer.enums.ChangeResourceType;
import com.fusioncareer.exception.ResumeErrorCode;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.ResumeFileMapper;
import com.fusioncareer.service.FileStorageService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.UserChangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 简历文件上传 Service 实现
 * <p>
 * 底层文件操作委托给 {@link FileStorageService}，本类仅负责简历相关的业务逻辑
 * （配额检查、元数据持久化、权限校验）。
 *
 * @author Xiong Heng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeFileServiceImpl extends ServiceImpl<ResumeFileMapper, ResumeFileEntity>
        implements ResumeFileService {

    private final UploadProperties uploadProperties;
    private final FileStorageService fileStorageService;
    private final UserChangeService changeService;

    // ── 接口实现 ──────────────────────────────────────────────────────────────

    @Transactional
    @Override
    public ResumeFileResponse upload(Long userId, MultipartFile file) {
        // 1. 基础校验（格式、大小）
        fileStorageService.validate(file);

        // 2. 配额检查
        long used = baseMapper.sumFileSizeByUserId(userId);
        if (used + file.getSize() > uploadProperties.getQuotaPerUser()) {
            long remainMB = (uploadProperties.getQuotaPerUser() - used) / (1024 * 1024);
            String msg = ResumeErrorCode.QUOTA_EXCEEDED.getMessage() + "（剩余 " + remainMB + " MB）";
            throw ServiceException.of(ResumeErrorCode.QUOTA_EXCEEDED, msg);
        }

        // 3. 存储文件
        String subDir = "resumes/" + userId;
        String relativePath = fileStorageService.store(file, subDir);

        // 4. 保存元数据
        ResumeFileEntity entity = new ResumeFileEntity();
        entity.setUserId(userId);
        entity.setOriginalName(file.getOriginalFilename());
        entity.setStoragePath(relativePath);
        entity.setFileSize(file.getSize());
        entity.setMimeType(file.getContentType());
        entity.setVersion(0L);
        LocalDateTime createNow = LocalDateTime.now();
        entity.setCreatedAt(createNow);
        entity.setUpdatedAt(createNow);
        save(entity);
        recordFileCreation(userId, entity);

        log.info("用户 {} 上传简历文件: {}, size={}KB", userId, file.getOriginalFilename(), file.getSize() / 1024);
        return toResponse(entity);
    }

    @Override
    public List<ResumeFileResponse> listByUser(Long userId) {
        List<ResumeFileEntity> entities = list(
                new LambdaQueryWrapper<ResumeFileEntity>()
                        .eq(ResumeFileEntity::getUserId, userId)
                        .isNull(ResumeFileEntity::getDeletedAt)
                        .orderByDesc(ResumeFileEntity::getCreatedAt)
        );
        return entities.stream().map(this::toResponse).toList();
    }

    @Override
    public List<ResumeFileResponse> listDeletedByUser(Long userId) {
        return list(new LambdaQueryWrapper<ResumeFileEntity>()
                .eq(ResumeFileEntity::getUserId, userId)
                .isNotNull(ResumeFileEntity::getDeletedAt)
                .orderByDesc(ResumeFileEntity::getDeletedAt)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    @Override
    public void delete(Long userId, Long fileId) {
        ResumeFileEntity readFile = getOwnFile(userId, fileId);
        Long updateVersion = updateDeletedState(
                userId, fileId, readFile.getVersion(), true);
        recordStateChange(
                userId,
                fileId,
                ChangeOperation.SOFT_DELETE,
                readFile.getVersion(),
                updateVersion,
                true,
                false,
                false,
                null,
                "将文件移入回收站");
        log.info("用户 {} 将简历文件移入回收站: id={}", userId, fileId);
    }

    @Transactional
    @Override
    public void restore(Long userId, Long fileId) {
        ResumeFileEntity readFile = getUserFile(userId, fileId, true);
        Long updateVersion = updateDeletedState(
                userId, fileId, readFile.getVersion(), false);
        recordStateChange(
                userId,
                fileId,
                ChangeOperation.RESTORE,
                readFile.getVersion(),
                updateVersion,
                false,
                true,
                null,
                false,
                "从回收站恢复文件");
        log.info("用户 {} 恢复简历文件: id={}", userId, fileId);
    }

    @Transactional
    @Override
    public Long restoreSnapshot(
            Long userId,
            Long fileId,
            Long expectedVersion,
            boolean deleted) {
        ResumeFileEntity readFile = getOwnFileIncludingDeleted(userId, fileId);
        if (expectedVersion == null || !expectedVersion.equals(readFile.getVersion())) {
            throw ServiceException.of(ResultCode.CONFLICT, "文件已发生变化，请刷新后重试");
        }
        boolean readDeleted = readFile.getDeletedAt() != null;
        if (readDeleted == deleted) {
            throw ServiceException.of(ResultCode.CONFLICT, "文件状态没有发生变化");
        }
        return updateDeletedState(userId, fileId, expectedVersion, deleted);
    }

    @Transactional
    @Override
    public void purge(Long userId, Long fileId) {
        ResumeFileEntity deleteFile = getUserFile(userId, fileId, null);
        fileStorageService.deleteFile(deleteFile.getStoragePath());
        removeById(fileId);
        log.info("用户 {} 永久删除简历文件: id={}", userId, fileId);
    }

    @Override
    public long getUsedBytes(Long userId) {
        return baseMapper.sumFileSizeByUserId(userId);
    }

    @Override
    public long countByUser(Long userId) {
        return count(new LambdaQueryWrapper<ResumeFileEntity>()
                .eq(ResumeFileEntity::getUserId, userId)
                .isNull(ResumeFileEntity::getDeletedAt));
    }

    @Override
    public ResumeFileEntity getOwnFile(Long userId, Long fileId) {
        return getUserFile(userId, fileId, false);
    }

    @Override
    public ResumeFileEntity getOwnFileIncludingDeleted(Long userId, Long fileId) {
        return getUserFile(userId, fileId, null);
    }

    private ResumeFileEntity getUserFile(Long userId, Long fileId, Boolean requireDeleted) {
        LambdaQueryWrapper<ResumeFileEntity> readQuery = new LambdaQueryWrapper<ResumeFileEntity>()
                .eq(ResumeFileEntity::getId, fileId)
                .eq(ResumeFileEntity::getUserId, userId);
        if (Boolean.TRUE.equals(requireDeleted)) {
            readQuery.isNotNull(ResumeFileEntity::getDeletedAt);
        } else if (Boolean.FALSE.equals(requireDeleted)) {
            readQuery.isNull(ResumeFileEntity::getDeletedAt);
        }
        ResumeFileEntity entity = getOne(readQuery);
        if (entity == null) {
            throw ServiceException.of(ResumeErrorCode.FILE_NOT_FOUND);
        }
        return entity;
    }

    private Long updateDeletedState(
            Long userId,
            Long fileId,
            Long expectedVersion,
            boolean deleted) {
        var updateFile = lambdaUpdate()
                .eq(ResumeFileEntity::getId, fileId)
                .eq(ResumeFileEntity::getUserId, userId)
                .eq(ResumeFileEntity::getVersion, expectedVersion);
        if (deleted) {
            updateFile.isNull(ResumeFileEntity::getDeletedAt)
                    .set(ResumeFileEntity::getDeletedAt, LocalDateTime.now());
        } else {
            updateFile.isNotNull(ResumeFileEntity::getDeletedAt)
                    .set(ResumeFileEntity::getDeletedAt, null);
        }
        boolean hasUpdated = updateFile
                .set(ResumeFileEntity::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .update();
        if (!hasUpdated) {
            throw ServiceException.of(ResultCode.CONFLICT, "文件已发生变化，请刷新后重试");
        }
        return expectedVersion + 1;
    }

    private void recordStateChange(
            Long userId,
            Long fileId,
            ChangeOperation operation,
            Long expectedVersion,
            Long appliedVersion,
            boolean beforeExists,
            boolean afterExists,
            Boolean beforeDeleted,
            Boolean afterDeleted,
            String reason) {
        Map<String, Object> readBefore = new java.util.LinkedHashMap<>();
        readBefore.put("deleted", beforeDeleted);
        Map<String, Object> readAfter = new java.util.LinkedHashMap<>();
        readAfter.put("deleted", afterDeleted);
        changeService.recordApplied(new UserChangeService.AppliedChange(
                userId,
                "FILE_UI",
                ChangeResourceType.RESUME_FILE,
                fileId.toString(),
                operation,
                List.of("deleted"),
                expectedVersion,
                appliedVersion,
                beforeExists,
                afterExists,
                readBefore,
                readAfter,
                reason));
    }

    private void recordFileCreation(Long userId, ResumeFileEntity readFile) {
        recordStateChange(
                userId,
                readFile.getId(),
                ChangeOperation.CREATE,
                null,
                readFile.getVersion(),
                false,
                true,
                null,
                false,
                "上传文件 " + readFile.getOriginalName());
    }

    @Override
    public Resource loadAsResource(String storagePath) {
        return fileStorageService.loadAsResource(storagePath);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private ResumeFileResponse toResponse(ResumeFileEntity entity) {
        ResumeFileResponse resp = BeanUtil.copyProperties(entity, ResumeFileResponse.class);
        if (entity.getDeletedAt() == null) {
            resp.setUrl(fileStorageService.buildUrl(entity.getStoragePath()));
        }
        return resp;
    }
}
