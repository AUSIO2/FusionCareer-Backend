package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.entity.JobPostQuestionEntity;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.mapper.JobPostQuestionMapper;
import com.fusioncareer.service.JobPostQuestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 岗位投递问卷题目 Service 实现
 * <p>
 * 采用「整组替换」策略：管理员每次保存问卷时，先删除旧题目再批量插入新题目。
 *
 * @author Xiong Heng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPostQuestionServiceImpl extends ServiceImpl<JobPostQuestionMapper, JobPostQuestionEntity>
        implements JobPostQuestionService {

    private final ObjectMapper objectMapper;

    @Transactional
    @Override
    public List<JobPostQuestionResponse> saveQuestions(Long jobPostId, List<JobPostQuestionRequest> questions) {
        // 1. 删除该岗位的旧问题
        remove(new LambdaQueryWrapper<JobPostQuestionEntity>()
                .eq(JobPostQuestionEntity::getJobPostId, jobPostId));

        // 2. 批量插入新问题
        List<JobPostQuestionEntity> entities = questions.stream().map(q -> {
            JobPostQuestionEntity entity = new JobPostQuestionEntity();
            entity.setJobPostId(jobPostId);
            entity.setSortOrder(q.getSortOrder() != null ? q.getSortOrder() : 0);
            entity.setTitle(q.getTitle());
            entity.setQuestionType(q.getQuestionType());
            entity.setOptions(serializeOptions(q.getOptions()));
            entity.setRequired(q.getRequired() != null ? q.getRequired() : true);
            entity.setPlaceholder(q.getPlaceholder());
            return entity;
        }).toList();

        saveBatch(entities);
        log.info("岗位 {} 问卷已更新，共 {} 道题", jobPostId, entities.size());

        return entities.stream().map(this::toResponse).toList();
    }

    @Override
    public List<JobPostQuestionResponse> listByJobPostId(Long jobPostId) {
        List<JobPostQuestionEntity> entities = list(
                new LambdaQueryWrapper<JobPostQuestionEntity>()
                        .eq(JobPostQuestionEntity::getJobPostId, jobPostId)
                        .orderByAsc(JobPostQuestionEntity::getSortOrder)
        );
        return entities.stream().map(this::toResponse).toList();
    }

    @Transactional
    @Override
    public void deleteByJobPostId(Long jobPostId) {
        remove(new LambdaQueryWrapper<JobPostQuestionEntity>()
                .eq(JobPostQuestionEntity::getJobPostId, jobPostId));
        log.info("岗位 {} 问卷已清空", jobPostId);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private String serializeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            log.error("选项序列化失败", e);
            return null;
        }
    }

    private List<String> deserializeOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(optionsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            log.error("选项反序列化失败: {}", optionsJson, e);
            return Collections.emptyList();
        }
    }

    private JobPostQuestionResponse toResponse(JobPostQuestionEntity entity) {
        JobPostQuestionResponse resp = new JobPostQuestionResponse();
        resp.setId(entity.getId());
        resp.setJobPostId(entity.getJobPostId());
        resp.setSortOrder(entity.getSortOrder());
        resp.setTitle(entity.getTitle());
        resp.setQuestionType(entity.getQuestionType());
        resp.setOptions(deserializeOptions(entity.getOptions()));
        resp.setRequired(entity.getRequired());
        resp.setPlaceholder(entity.getPlaceholder());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }
}
