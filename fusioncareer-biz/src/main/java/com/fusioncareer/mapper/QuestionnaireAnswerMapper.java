package com.fusioncareer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fusioncareer.dto.JobPostApplicationCount;
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
            "AND job_post_id IN",
            "<foreach collection='readJobIds' item='readJobId' open='(' separator=',' close=')'>",
            "#{readJobId}",
            "</foreach>",
            "GROUP BY job_post_id",
            "</script>"
    })
    List<JobPostApplicationCount> countApplications(@Param("readJobIds") Collection<Long> readJobIds);
}
