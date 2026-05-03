package com.fusioncareer.service.impl;

import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.exception.ResumeErrorCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 通用文件存储服务实现
 * <p>
 * 底层文件操作（校验、存储、读取、删除）统一封装于此，
 * 供 ResumeFileService、ApplicationAnswerService 等业务模块复用。
 *
 * @author Xiong Heng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private final UploadProperties uploadProperties;

    @Override
    public String store(MultipartFile file, String subDir) {
        String originalName = file.getOriginalFilename();
        String ext = extractExt(originalName);
        String dateDir = LocalDate.now().toString();
        String savedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String relativePath = subDir + "/" + dateDir + "/" + savedName;
        Path target = Paths.get(uploadProperties.getBaseDir(), relativePath);

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("文件存储失败, path={}", target, e);
            throw ServiceException.of(ResumeErrorCode.SAVE_FAILED);
        }

        return relativePath;
    }

    @Override
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ServiceException.of(ResumeErrorCode.EMPTY_FILE);
        }
        String originalName = file.getOriginalFilename();
        String ext = extractExt(originalName);
        String mimeType = file.getContentType();

        if (!uploadProperties.allowedExtSet().contains(ext.toLowerCase())) {
            throw ServiceException.of(ResumeErrorCode.UNSUPPORTED_FILE_FORMAT);
        }
        if (mimeType == null || !uploadProperties.allowedMimeSet().contains(mimeType.toLowerCase())) {
            throw ServiceException.of(ResumeErrorCode.INVALID_MIME_TYPE);
        }
        if (file.getSize() > uploadProperties.getMaxSingleFileBytes()) {
            throw ServiceException.of(ResumeErrorCode.FILE_TOO_LARGE);
        }
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

    @Override
    public void deleteFile(String storagePath) {
        Path filePath = Paths.get(uploadProperties.getBaseDir(), storagePath);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("磁盘文件删除失败, path={}", filePath, e);
        }
    }

    @Override
    public String buildUrl(String storagePath) {
        return uploadProperties.getUrlPrefix() + "/" + storagePath;
    }

    private String extractExt(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw ServiceException.of(ResumeErrorCode.INVALID_FILE_NAME);
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
