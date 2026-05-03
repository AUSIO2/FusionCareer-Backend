package com.fusioncareer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fusioncareer.enums.QuestionType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 岗位投递问卷题目实体
 *
 * @author Xiong Heng
 */
@Data
@TableName("fc_job_post_question")
public class JobPostQuestionEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 问题ID（雪花算法） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属岗位ID */
    private Long jobPostId;

    /** 排序序号（升序） */
    private Integer sortOrder;

    /** 问题标题 */
    private String title;

    /** 题目类型：1-单行文本 2-多行文本 3-单选 4-多选 5-文件上传 */
    private QuestionType questionType;

    /** 选项列表JSON（单选/多选时使用） */
    private String options;

    /** 是否必填：1-必填 0-选填 */
    private Boolean required;

    /** 输入提示文字 */
    private String placeholder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
