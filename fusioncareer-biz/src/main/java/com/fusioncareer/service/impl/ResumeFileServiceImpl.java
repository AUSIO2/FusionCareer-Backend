package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.exception.ResumeErrorCode;
import com.fusioncareer.mapper.ResumeFileMapper;
import com.fusioncareer.service.ResumeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 简历文件上传 Service 实现
 * <p>
 * 存储策略：本地磁盘（{upload.base-dir}/resumes/{userId}/{date}/{uuid.ext}）
 * 后续可无缝替换为 OSS，只需修改此实现类。
 * <p>
 * 所有配置项（路径、配额、文件类型白名单等）均从 {@link UploadProperties} 读取，
 * 对应 application.yml 中的 {@code upload.*} 配置块，不允许在此类中硬编码。
 *
 * @author Xiong Heng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeFileServiceImpl extends ServiceImpl<ResumeFileMapper, ResumeFileEntity>
        implements ResumeFileService {

    private final UploadProperties uploadProperties;

    // ── 接口实现 ──────────────────────────────────────────────────────────────

    @Transactional
    @Override
    public ResumeFileResponse upload(Long userId, MultipartFile file) {
        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            throw ServiceException.of(ResumeErrorCode.EMPTY_FILE);
        }
        String originalName = file.getOriginalFilename();
        String ext = extractExt(originalName);
        String mimeType = file.getContentType();

        validateFile(ext, mimeType, file.getSize());

        // 2. 配额检查
        long used = baseMapper.sumFileSizeByUserId(userId);
        if (used + file.getSize() > uploadProperties.getQuotaPerUser()) {
            long remainMB = (uploadProperties.getQuotaPerUser() - used) / (1024 * 1024);
            String msg = ResumeErrorCode.QUOTA_EXCEEDED.getMessage() + "（剩余 " + remainMB + " MB）";
            throw ServiceException.of(ResumeErrorCode.QUOTA_EXCEEDED, msg);
        }

        // 3. 存储文件
        // 路径：{baseDir}/resumes/{userId}/{date}/{uuid}.{ext}
        String dateDir = LocalDate.now().toString();  // e.g. 2026-05-03
        String savedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String relativePath = "resumes/" + userId + "/" + dateDir + "/" + savedName;
        Path target = Paths.get(uploadProperties.getBaseDir(), relativePath);

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("简历文件存储失败, userId={}, path={}", userId, target, e);
            throw ServiceException.of(ResumeErrorCode.SAVE_FAILED);
        }

        // 4. 保存元数据
        ResumeFileEntity entity = new ResumeFileEntity();
        entity.setUserId(userId);
        entity.setOriginalName(originalName);
        entity.setStoragePath(relativePath);
        entity.setFileSize(file.getSize());
        entity.setMimeType(mimeType);
        entity.setCreatedAt(LocalDateTime.now());
        save(entity);

        log.info("用户 {} 上传简历文件: {}, size={}KB", userId, originalName, file.getSize() / 1024);
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
        Path filePath = Paths.get(uploadProperties.getBaseDir(), entity.getStoragePath());
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("磁盘文件删除失败, path={}", filePath, e);
            // 不阻断流程，继续删数据库记录
        }

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
        try {
            Path filePath = Paths.get(uploadProperties.getBaseDir(), storagePath);
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw ServiceException.of(ResumeErrorCode.FILE_NOT_FOUND);
            }
            return resource;
        } catch (java.net.MalformedURLException e) {
            log.error("构建文件 URL 失败, path={}", storagePath, e);
            throw ServiceException.of(ResumeErrorCode.SAVE_FAILED);
        }
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private void validateFile(String ext, String mimeType, long size) {
        if (!uploadProperties.allowedExtSet().contains(ext.toLowerCase())) {
            throw ServiceException.of(ResumeErrorCode.UNSUPPORTED_FILE_FORMAT);
        }
        if (mimeType == null || !uploadProperties.allowedMimeSet().contains(mimeType.toLowerCase())) {
            throw ServiceException.of(ResumeErrorCode.INVALID_MIME_TYPE);
        }
        if (size > uploadProperties.getMaxSingleFileBytes()) {
            throw ServiceException.of(ResumeErrorCode.FILE_TOO_LARGE);
        }
    }

    private String extractExt(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw ServiceException.of(ResumeErrorCode.INVALID_FILE_NAME);
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    private ResumeFileResponse toResponse(ResumeFileEntity entity) {
        ResumeFileResponse resp = new ResumeFileResponse();
        resp.setId(entity.getId());
        resp.setOriginalName(entity.getOriginalName());
        resp.setFileSize(entity.getFileSize());
        resp.setMimeType(entity.getMimeType());
        resp.setCreatedAt(entity.getCreatedAt());
        // 拼接可访问的 URL
        resp.setUrl(uploadProperties.getUrlPrefix() + "/" + entity.getStoragePath());
        return resp;
    }
}
