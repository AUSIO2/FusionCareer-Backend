package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.QuestionnaireAnswerResponse;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.mapper.QuestionnaireAnswerMapper;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 学生问卷作答 Service 实现
 * <p>
 * 重复提交时采用「覆盖更新」策略：相同 (userId, jobPostId) 组合只保留最新作答。
 *
 * @author Xiong Heng
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionnaireAnswerServiceImpl extends ServiceImpl<QuestionnaireAnswerMapper, QuestionnaireAnswerEntity>
        implements QuestionnaireAnswerService {

    private final UserService userService;

    @Transactional
    @Override
    public QuestionnaireAnswerResponse submit(Long userId, QuestionnaireSubmitRequest request) {
        // 查找是否已有作答记录
        QuestionnaireAnswerEntity existing = getOne(
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getJobPostId, request.getJobPostId())
                        .eq(QuestionnaireAnswerEntity::getUserId, userId)
        );

        if (existing != null) {
            // 覆盖更新
            existing.setAnswers(request.getAnswers());
            existing.setUpdatedAt(LocalDateTime.now());
            updateById(existing);
            log.info("用户 {} 覆盖更新岗位 {} 的问卷作答", userId, request.getJobPostId());
            return toResponse(existing);
        }

        // 新建作答记录
        QuestionnaireAnswerEntity entity = new QuestionnaireAnswerEntity();
        entity.setJobPostId(request.getJobPostId());
        entity.setUserId(userId);
        entity.setAnswers(request.getAnswers());
        save(entity);
        log.info("用户 {} 提交岗位 {} 的问卷作答", userId, request.getJobPostId());
        return toResponse(entity);
    }

    @Override
    public PageResult<QuestionnaireAnswerResponse> listByJobPostId(Long jobPostId, int page, int size) {
        Page<QuestionnaireAnswerEntity> result = page(
                new Page<>(page, size),
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId)
                        .orderByDesc(QuestionnaireAnswerEntity::getCreatedAt)
        );

        PageResult<QuestionnaireAnswerResponse> pageResult = new PageResult<>(result.getTotal(), page, size);
        result.getRecords().forEach(e -> pageResult.add(toResponse(e)));
        return pageResult;
    }

    @Override
    public QuestionnaireAnswerResponse getByUserAndJobPost(Long userId, Long jobPostId) {
        QuestionnaireAnswerEntity entity = getOne(
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getUserId, userId)
                        .eq(QuestionnaireAnswerEntity::getJobPostId, jobPostId)
        );
        return entity == null ? null : toResponse(entity);
    }

    @Override
    public QuestionnaireAnswerResponse getDetail(Long id) {
        return toResponse(getById(id));
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private QuestionnaireAnswerResponse toResponse(QuestionnaireAnswerEntity entity) {
        if (entity == null) return null;
        QuestionnaireAnswerResponse resp = new QuestionnaireAnswerResponse();
        resp.setId(entity.getId());
        resp.setJobPostId(entity.getJobPostId());
        resp.setUserId(entity.getUserId());
        resp.setAnswers(entity.getAnswers());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setUpdatedAt(entity.getUpdatedAt());

        // 填充学生基本信息（用户名、学工号）
        try {
            UserEntity user = userService.getById(entity.getUserId());
            if (user != null) {
                resp.setUsername(user.getUsername());
                resp.setStudentId(user.getStudentId());
            }
        } catch (Exception e) {
            log.warn("查询投递用户信息失败, userId={}", entity.getUserId(), e);
        }

        return resp;
    }
}
