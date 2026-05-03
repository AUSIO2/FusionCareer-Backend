package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 学生问卷作答实体
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_questionnaire_answer")
public class QuestionnaireAnswerEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 作答记录ID（雪花算法） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属岗位ID */
    private Long jobPostId;

    /** 投递学生用户ID */
    private Long userId;

    /** 作答内容JSON */
    private String answers;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
