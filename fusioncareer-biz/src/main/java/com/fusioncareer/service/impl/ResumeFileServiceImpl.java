package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.exception.ResumeErrorCode;
import com.fusioncareer.mapper.ResumeFileMapper;
import com.fusioncareer.service.FileStorageService;
import com.fusioncareer.service.ResumeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

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
        entity.setCreatedAt(LocalDateTime.now());
        save(entity);

        log.info("用户 {} 上传简历文件: {}, size={}KB", userId, file.getOriginalFilename(), file.getSize() / 1024);
        return toResponse(entity);
    }

    @Override
    public List<ResumeFileResponse> listByUser(Long userId) {
        List<ResumeFileEntity> entities = list(
                new LambdaQueryWrapper<ResumeFileEntity>()
                        .eq(ResumeFileEntity::getUserId, userId)
                        .orderByDesc(ResumeFileEntity::getCreatedAt)
        );
        return entities.stream().map(this::toResponse).toList();
    }

    @Transactional
    @Override
    public void delete(Long userId, Long fileId) {
        ResumeFileEntity entity = getById(fileId);
        if (entity == null) {
            throw ServiceException.of(ResumeErrorCode.FILE_NOT_FOUND);
        }
        if (!entity.getUserId().equals(userId)) {
            throw ServiceException.of(ResumeErrorCode.DELETE_FORBIDDEN);
        }

        // 删除磁盘文件
        fileStorageService.deleteFile(entity.getStoragePath());

        removeById(fileId);
        log.info("用户 {} 删除简历文件: id={}, path={}", userId, fileId, entity.getStoragePath());
    }

    @Override
    public long getUsedBytes(Long userId) {
        return baseMapper.sumFileSizeByUserId(userId);
    }

    @Override
    public ResumeFileEntity getOwnFile(Long userId, Long fileId) {
        ResumeFileEntity entity = getById(fileId);
        if (entity == null) {
            throw ServiceException.of(ResumeErrorCode.FILE_NOT_FOUND);
        }
        if (!entity.getUserId().equals(userId)) {
            throw ServiceException.of(ResumeErrorCode.DELETE_FORBIDDEN);
        }
        return entity;
    }

    @Override
    public Resource loadAsResource(String storagePath) {
        return fileStorageService.loadAsResource(storagePath);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private ResumeFileResponse toResponse(ResumeFileEntity entity) {
        ResumeFileResponse resp = new ResumeFileResponse();
        resp.setId(entity.getId());
        resp.setOriginalName(entity.getOriginalName());
        resp.setFileSize(entity.getFileSize());
        resp.setMimeType(entity.getMimeType());
        resp.setCreatedAt(entity.getCreatedAt());
        // 拼接可访问的 URL
        resp.setUrl(fileStorageService.buildUrl(entity.getStoragePath()));
        return resp;
    }
}
