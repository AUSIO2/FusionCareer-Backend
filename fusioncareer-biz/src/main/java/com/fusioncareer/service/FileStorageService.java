package com.fusioncareer.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文件存储服务
 * <p>
 * 将文件上传、校验、存储、下载等底层能力从 ResumeFileService 中抽象出来，
 * 供简历文件上传、问卷文件上传等不同业务场景复用。
 *
 * @author Xiong Heng
 */
public interface FileStorageService {

    /**
     * 存储文件到磁盘
     *
     * @param file      上传的文件
     * @param subDir    子目录（如 "resumes/{userId}" 或 "questionnaire/{userId}"）
     * @return 相对存储路径（相对于 upload.base-dir）
     */
    String store(MultipartFile file, String subDir);

    /**
     * 校验文件格式、大小等
     *
     * @param file 上传的文件
     * @throws com.fusioncareer.exception.ServiceException 校验不通过时抛出
     */
    void validate(MultipartFile file);

    /**
     * 根据相对路径加载文件为 Resource
     *
     * @param storagePath 相对存储路径
     * @return Spring Resource
     */
    Resource loadAsResource(String storagePath);

    /**
     * 删除磁盘上的文件
     *
     * @param storagePath 相对存储路径
     */
    void deleteFile(String storagePath);

    /**
     * 拼接文件的可访问 URL
     *
     * @param storagePath 相对存储路径
     * @return 完整的访问 URL
     */
    String buildUrl(String storagePath);
}
