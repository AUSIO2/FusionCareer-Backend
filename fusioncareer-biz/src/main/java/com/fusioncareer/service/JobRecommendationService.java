package com.fusioncareer.service;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.req.JobRecommendationRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.JobPostMapper;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** Candidate retrieval and public card projection; no model calls or user writes. */
@Service
@RequiredArgsConstructor
public class JobRecommendationService {
    public static final String ALGORITHM_VERSION = "1f3d8a7";

    private final JobPostMapper jobs;
    private final UserProfileService profiles;
    private final ObjectMapper mapper;
    private final Validator validator;

    public JobRecommendationRequest validate(Map<String, Object> input) {
        if (!Set.of("jobCategories", "workCities", "keywords", "recruitType", "text").containsAll(input.keySet())) {
            throw invalid();
        }
        try {
            var query = mapper.convertValue(input, JobRecommendationRequest.class);
            if (!validator.validate(query).isEmpty() || query.getJobCategories() == null
                    || query.getWorkCities() == null || query.getKeywords() == null) throw invalid();
            return query;
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    public Map<String, Object> candidates(Long userId, Map<String, Object> input) {
        var request = validate(input);
        var query = visibleQuery();
        query.eq(request.getRecruitType() != null, JobPostEntity::getRecruitType, request.getRecruitType());
        if (!request.getJobCategories().isEmpty()) {
            var exact = request.getJobCategories().stream().filter(c -> c != JobCategory.MEDIA).toList();
            boolean media = request.getJobCategories().contains(JobCategory.MEDIA);
            query.and(branch -> {
                if (!exact.isEmpty()) branch.nested(q -> {
                    q.in(JobPostEntity::getJobCategory, exact);
                    keywordsLike(q, request.getKeywords());
                });
                if (media) {
                    if (!exact.isEmpty()) branch.or();
                    branch.nested(m -> {
                        textLike(m, "媒体");
                        request.getKeywords().forEach(k -> { m.or(); textLike(m, k); });
                    });
                }
            });
        }
        if (!request.getWorkCities().isEmpty()) query.and(cityGroup -> {
            for (String city : request.getWorkCities()) {
                String stem = city.trim().replaceFirst("市$", "");
                cityGroup.or().nested(c -> c.like(JobPostEntity::getWorkCity, stem)
                        .or().apply("JSON_SEARCH(work_cities, 'one', {0}) IS NOT NULL", "%" + stem + "%")
                        .or().like(JobPostEntity::getWorkProvince, stem)
                        .or().like(JobPostEntity::getWorkLocation, stem));
            }
        });
        if (request.getJobCategories().isEmpty()) keywordsLike(query, request.getKeywords());
        query.orderByDesc(JobPostEntity::getCreatedAt).orderByDesc(JobPostEntity::getId);
        // The latest algorithm removes duplicate companies before ranking, so
        // provide a wider but still bounded candidate window.
        var page = jobs.selectPage(new Page<JobPostEntity>(1, 80), query);
        Map<String, Object> resume = new LinkedHashMap<>();
        var profile = profiles.getProfile(userId);
        if (profile != null) {
            resume.put("major", profile.getMajor());
            resume.put("eduLevel", profile.getEduLevel());
            resume.put("grade", profile.getGrade());
        }
        return Map.of("jobs", page.getRecords().stream().map(this::publicJob).toList(),
                "total", page.getTotal(), "resume", resume);
    }

    public Map<String, Object> hydrate(Map<String, Object> presentation) {
        Map<String, Object> result = new LinkedHashMap<>(presentation);
        Object raw = result.get("jobIds");
        if (!(raw instanceof List<?> ids)) return result;
        List<Long> requested = ids.stream().limit(10).map(x -> Long.valueOf(x.toString())).distinct().toList();
        Map<Long, JobPostEntity> found = new HashMap<>();
        if (!requested.isEmpty()) jobs.selectList(visibleQuery().in(JobPostEntity::getId, requested))
                .forEach(j -> found.put(j.getId(), j));
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Long id : requested) {
            var job = found.get(id);
            if (job == null) cards.add(Map.of("id", id.toString(), "available", false,
                    "positionName", "岗位已下线或截止"));
            else {
                var card = publicJob(job);
                card.put("available", true);
                List<String> labels = new ArrayList<>();
                if (Boolean.TRUE.equals(job.getRecommended())) labels.add("学院推荐");
                if (presentation.get("filters") instanceof Map<?, ?> filters && filters.get("workCities") instanceof List<?> cities) {
                    String locations = String.valueOf(job.getWorkCity()) + job.getWorkCities() + job.getWorkLocation();
                    if (cities.stream().anyMatch(c -> locations.contains(c.toString().replaceFirst("市$", "")))) labels.add("符合意向城市");
                }
                card.put("labels", labels);
                if (presentation.get("reasons") instanceof Map<?, ?> reasons
                        && reasons.get(id.toString()) instanceof String reason
                        && !reason.isBlank()) {
                    card.put("recommendReason", reason);
                }
                cards.add(card);
            }
        }
        result.put("jobs", cards);
        return result;
    }

    private Map<String, Object> publicJob(JobPostEntity entity) {
        JobPostResponse response = BeanUtil.copyProperties(entity, JobPostResponse.class);
        Map<String, Object> result = mapper.convertValue(response, new TypeReference<>() { });
        result.remove("recycleReason"); result.remove("recycledAt"); result.remove("createdBy");
        result.put("id", entity.getId().toString());
        return result;
    }

    private LambdaQueryWrapper<JobPostEntity> visibleQuery() {
        var today = LocalDate.now();
        return new LambdaQueryWrapper<JobPostEntity>().eq(JobPostEntity::getStatus, JobPostStatus.PUBLISHED)
                .and(q -> q.isNull(JobPostEntity::getApplicationDeadline).or().ge(JobPostEntity::getApplicationDeadline, today))
                .and(q -> q.isNull(JobPostEntity::getWorkEndDate).or().ge(JobPostEntity::getWorkEndDate, today));
    }

    private void textLike(LambdaQueryWrapper<JobPostEntity> query, String text) {
        query.like(JobPostEntity::getPositionName, text).or().like(JobPostEntity::getCompanyName, text)
                .or().like(JobPostEntity::getJobDesc, text).or().like(JobPostEntity::getReqSkills, text)
                .or().like(JobPostEntity::getReqOther, text).or().like(JobPostEntity::getReqMajor, text);
    }

    private void keywordsLike(LambdaQueryWrapper<JobPostEntity> query, List<String> keywords) {
        if (!keywords.isEmpty()) query.and(group -> keywords.forEach(k -> {
            group.or(); group.nested(q -> textLike(q, k));
        }));
    }

    private ServiceException invalid() {
        return ServiceException.of(ResultCode.VALIDATE_FAILED, "推荐筛选条件格式无效");
    }
}
