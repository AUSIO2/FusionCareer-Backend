package com.fusioncareer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fusioncareer.entity.ResumeFileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 简历文件 Mapper
 *
 * @author Xiong Heng
 */
@Mapper
public interface ResumeFileMapper extends BaseMapper<ResumeFileEntity> {

    /**
     * 统计某用户当前已用存储空间（字节）
     */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM fc_resume_file WHERE user_id = #{userId}")
    long sumFileSizeByUserId(Long userId);
}
