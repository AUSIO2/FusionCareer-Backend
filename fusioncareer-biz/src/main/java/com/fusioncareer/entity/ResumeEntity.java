package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户简历+作品集实体
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_resume")
public class ResumeEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long userId;
    private String personalIntro;
    private String basicInfo;
    private String education;
    private String internship;
    private String campus;
    private String awards;
    private String skills;
    private String portfolio;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
