package com.fusioncareer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fusioncareer.dto.JobPostApplicationCount;
import com.fusioncareer.dto.QuestionnaireApplicationCount;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 学生问卷作答 Mapper
 *
 * @author Xiong Heng
 */
@Mapper
public interface QuestionnaireAnswerMapper extends BaseMapper<QuestionnaireAnswerEntity> {

    @Select({
            "<script>",
            "SELECT job_post_id AS jobPostId, COUNT(*) AS applicationCount",
            "FROM fc_questionnaire_answer",
            "WHERE submission_status IN (1, 2)",
            "AND deleted_at IS NULL",
            "AND job_post_id IN",
            "<foreach collection='readJobIds' item='readJobId' open='(' separator=',' close=')'>",
            "#{readJobId}",
            "</foreach>",
            "GROUP BY job_post_id",
            "</script>"
    })
    List<JobPostApplicationCount> countApplications(@Param("readJobIds") Collection<Long> readJobIds);

    @Select({
            "SELECT COUNT(*) AS total,",
            "COALESCE(SUM(CASE WHEN submission_status = 0 THEN 1 ELSE 0 END), 0) AS draft,",
            "COALESCE(SUM(CASE WHEN submission_status IS NULL OR submission_status = 1 THEN 1 ELSE 0 END), 0) AS pending,",
            "COALESCE(SUM(CASE WHEN submission_status = 2 THEN 1 ELSE 0 END), 0) AS done",
            "FROM fc_questionnaire_answer",
            "WHERE user_id = #{readUserId}",
            "AND deleted_at IS NULL"
    })
    QuestionnaireApplicationCount countMyApplications(@Param("readUserId") Long userId);
}
