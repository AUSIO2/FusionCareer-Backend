package com.fusioncareer;

import com.fusioncareer.dto.req.AiInteraction;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.req.JobRecommendationRequest;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.*;
import com.fusioncareer.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobRecommendationTest {
    @Autowired JobRecommendationService recommendations;
    @Autowired JobPostService jobs;
    @Autowired UserService users;
    @Autowired AiChatService chats;
    @Autowired AgentContextService contexts;
    @Autowired AgentToolService tools;

    @Test void candidatesRespectVisibilityAndMultipleCities() {
        var user = user();
        var valid = job("编辑", JobPostStatus.PUBLISHED, List.of("北京", "杭州市"), "负责视频剪辑");
        job("下线编辑", JobPostStatus.OFFLINE, List.of("杭州"), "负责视频剪辑");
        var expired = job("已截止", JobPostStatus.PUBLISHED, List.of("杭州"), "视频剪辑");
        expired.setApplicationDeadline(LocalDate.now().minusDays(1)); jobs.updateById(expired);
        job("异地", JobPostStatus.PUBLISHED, List.of("广州"), "视频剪辑");
        var result = recommendations.candidates(user.getId(), Map.of("workCities", List.of("杭州", "上海"),
                "keywords", List.of("视频剪辑", "播音")));
        assertThat(ids(result)).containsExactly(valid.getId().toString());
        String signed = contexts.issueContext(user.getId(), "recommend-run", 1L, "request", List.of("job:search"));
        assertThatThrownBy(() -> tools.executeTool(signed, "recommendation_candidates", Map.of()))
                .hasMessageContaining("Agent");
    }

    @Test void mediaBranchIsSoftAndDoesNotConstrainOtherCategories() {
        var user = user();
        var media = job("记者", JobPostStatus.PUBLISHED, List.of("上海"), "媒体招聘");
        var enterprise = job("产品", JobPostStatus.PUBLISHED, List.of("上海"), "产品运营");
        assertThat(ids(recommendations.candidates(user.getId(), Map.of("jobCategories", List.of("MEDIA")))))
                .contains(media.getId().toString()).doesNotContain(enterprise.getId().toString());
        assertThat(ids(recommendations.candidates(user.getId(), Map.of("jobCategories", List.of("MEDIA", "ENTERPRISE")))))
                .contains(media.getId().toString(), enterprise.getId().toString());
    }

    @Test void presentationSurvivesHistoryButCannotOutliveCancelledRunOrVisibleJob() {
        var user = user();
        var job = job("记者", JobPostStatus.PUBLISHED, List.of("上海"), "媒体岗位");
        var request = new AiMessageRequest("cards-1", "推荐岗位", List.of(),
                new AiInteraction(1, "job_recommendation", new JobRecommendationRequest()), null);
        var run = chats.startRun(user.getId(), request);
        assertThat(chats.savePresentation(user.getId(), run.session().epoch(), run.assistantMessage().runId(),
                Map.of("type", "job_results", "jobIds", List.of(job.getId().toString()), "method", "rules",
                        "reasons", Map.of(job.getId().toString(), "岗位方向与偏好一致。")))).isNotNull();
        chats.completeRun(user.getId(), run.session().epoch(), run.assistantMessage().runId(), "推荐结果", "test", "stop", 1, 1);
        var presentation = chats.readMessages(user.getId(), null, 10).messages().get(0).presentation();
        assertThat(presentation).containsKey("jobs").containsEntry("algorithmVersion", JobRecommendationService.ALGORITHM_VERSION);
        assertThat((List<Map<String,Object>>) presentation.get("jobs")).singleElement()
                .satisfies(card -> assertThat(card).containsEntry("recommendReason", "岗位方向与偏好一致。"));
        var changed = new JobRecommendationRequest(); changed.setWorkCities(List.of("北京"));
        assertThatThrownBy(() -> chats.startRun(user.getId(), new AiMessageRequest("cards-1", "推荐岗位", List.of(),
                new AiInteraction(1, "job_recommendation", changed), null))).hasMessageContaining("不同消息");
        jobs.recycleJob(job.getId(), "测试下线");
        var history = chats.readMessages(user.getId(), null, 10).messages().get(0).presentation();
        assertThat((List<Map<String,Object>>) history.get("jobs")).singleElement()
                .satisfies(card -> assertThat(card).containsEntry("available", false));
        var cancelled = chats.startRun(user.getId(), new AiMessageRequest("cards-2", "再推荐", List.of()));
        chats.cancelRun(user.getId());
        assertThat(chats.savePresentation(user.getId(), cancelled.session().epoch(), cancelled.assistantMessage().runId(),
                Map.of("type", "job_results", "jobIds", List.of(job.getId().toString())))).isNull();
    }

    @Test void actionReferencesBelongToTheSameUserAndRun() {
        var user = user();
        var run = chats.startRun(user.getId(), new AiMessageRequest("proposal-cards", "把专业改为新闻学", List.of()));
        String signed = contexts.issueContext(user.getId(), run.assistantMessage().runId(), run.session().epoch(),
                "proposal-cards", List.of("profile:propose"));
        var proposal = (Map<String, Object>) tools.executeTool(signed, "propose_profile_patch", Map.of(
                "changes", List.of(Map.of("field", "major", "operation", "SET", "value", "新闻学")),
                "reason", "用户要求更新专业"), "proposal-call");
        var card = chats.savePresentation(user.getId(), run.session().epoch(), run.assistantMessage().runId(),
                Map.of("actionId", proposal.get("actionId")));
        assertThat((List<String>) card.get("actionIds")).containsExactly(proposal.get("actionId").toString());
        var other = user();
        var otherRun = chats.startRun(other.getId(), new AiMessageRequest("other-proposal", "修改专业", List.of()));
        assertThatThrownBy(() -> chats.savePresentation(other.getId(), otherRun.session().epoch(),
                otherRun.assistantMessage().runId(), Map.of("actionId", proposal.get("actionId"))))
                .hasMessageContaining("不属于当前运行");
    }

    private List<String> ids(Map<String,Object> result) {
        return ((List<Map<String,Object>>) result.get("jobs")).stream().map(j -> j.get("id").toString()).toList();
    }
    private UserEntity user() {
        var u = new UserEntity(); u.setStudentId("rec-" + UUID.randomUUID().toString().substring(0, 8)); u.setUsername("推荐测试");
        u.setRole(UserRole.NORMAL);u.setStatus(UserStatus.NORMAL);users.save(u);return u;
    }
    private JobPostEntity job(String title, JobPostStatus status, List<String> cities, String description) {
        var j = new JobPostEntity();j.setCompanyName("测试单位");j.setPositionName(title);
        j.setRecruitType(RecruitType.DAILY_INTERNSHIP);j.setSourceType(SourceType.PLATFORM);
        j.setJobCategory(JobCategory.ENTERPRISE);j.setStatus(status);j.setWorkCities(cities);j.setWorkCity(cities.get(0));
        j.setJobDesc(description);j.setApplicationDeadline(LocalDate.now().plusDays(10));jobs.save(j);return j;
    }
}
