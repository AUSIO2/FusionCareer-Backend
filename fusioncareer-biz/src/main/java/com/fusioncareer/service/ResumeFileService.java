package com.fusioncareer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.entity.ResumeFileEntity;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 简历文件上传 Service
 *
 * @author Xiong Heng
 */
public interface ResumeFileService extends IService<ResumeFileEntity> {

    /**
     * 上传简历文件
     *
     * @param userId 当前用户ID
     * @param file   上传的文件
     * @return 文件元数据响应
     */
    ResumeFileResponse upload(Long userId, MultipartFile file);

    /**
     * 列出用户所有上传的简历文件
     *
     * @param userId 当前用户ID
     * @return 文件列表
     */
    List<ResumeFileResponse> listByUser(Long userId);

    /** 列出当前用户回收站文件。 */
    List<ResumeFileResponse> listDeletedByUser(Long userId);

    /**
     * 下载指定文件（只能下载自己的）
     *
     * @param userId 当前用户ID
     * @param fileId 文件ID
     * @return 包含文件流的 Resource，调用方负责设置响应头
     */
    ResumeFileEntity getOwnFile(Long userId, Long fileId);

    /**
     * 获取文件的 Resource（用于流式下载）
     *
     * @param storagePath 文件相对存储路径
     * @return Spring Resource
     */
    Resource loadAsResource(String storagePath);

    /**
     * 将指定文件移入回收站（只能操作自己的）
     *
     * @param userId 当前用户ID
     * @param fileId 文件ID
     */
    void delete(Long userId, Long fileId);

    /** 从回收站恢复文件。 */
    void restore(Long userId, Long fileId);

    /**
     * 永久删除文件，仅供明确的数据清除流程和测试清理使用。
     */
    void purge(Long userId, Long fileId);

    /**
     * 查询用户已使用的存储空间（字节）
     *
     * @param userId 当前用户ID
     * @return 已用字节数
     */
    long getUsedBytes(Long userId);

    long countByUser(Long userId);
}
