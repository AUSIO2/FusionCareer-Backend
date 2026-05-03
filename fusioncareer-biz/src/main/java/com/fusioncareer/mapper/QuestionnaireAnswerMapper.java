package com.fusioncareer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学生问卷作答 Mapper
 *
 * @author Xiong Heng
 */
@Mapper
public interface QuestionnaireAnswerMapper extends BaseMapper<QuestionnaireAnswerEntity> {
}
