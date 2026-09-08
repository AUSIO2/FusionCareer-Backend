package com.fusioncareer;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobPostQueryTest {

    @Autowired
    private JobPostService readJobService;

    @Autowired
    private QuestionnaireAnswerService readAnswerService;

    @BeforeEach
    void createJobs() {
        LocalDate readToday = LocalDate.now();
        createJob("alpha", "上海", "上海", 100, 200,
                readToday.plusDays(12), LocalDateTime.of(2026, 8, 1, 8, 0));
        createJob("beta", "上海", "上海", 250, 400,
                readToday.plusDays(2), LocalDateTime.of(2026, 8, 3, 8, 0));
        createJob("gamma", "广东", "广州", 500, 600,
                null, LocalDateTime.of(2026, 8, 2, 8, 0));
        createJob("delta", "广东", "深圳", 150, 300,
                readToday.plusDays(22), LocalDateTime.of(2026, 8, 4, 8, 0));
    }

    @Test
    void filterProvince() {
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setWorkProvince("上海");

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void filterCity() {
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setWorkCity("广州");

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactly("gamma");
    }

    @Test
    void filterSalary() {
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setSalaryMin(180);
        readQuery.setSalaryMax(260);

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactlyInAnyOrder("alpha", "beta", "delta");
    }

    @Test
    void sortDeadline() {
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setSortBy(JobPostSort.DEADLINE);

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactly("beta", "alpha", "delta", "gamma");
    }

    @Test
    void sortNewest() {
        JobPostQueryRequest readQuery = new JobPostQueryRequest();

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactly("delta", "beta", "gamma", "alpha");
    }

    @Test
    void readRecommendations() {
        readJobService.lambdaUpdate()
                .in(JobPostEntity::getPositionName, "alpha", "gamma")
                .set(JobPostEntity::getRecommended, true)
                .update();
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setRecommended(true);

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactlyInAnyOrder("alpha", "gamma");
    }

    @Test
    void countApplications() {
        JobPostEntity readJob = readJobService.lambdaQuery()
                .eq(JobPostEntity::getPositionName, "alpha")
                .one();
        createAnswer(readJob.getId(), 101L, QuestionnaireSubmissionStatus.DRAFT);
        createAnswer(readJob.getId(), 102L, QuestionnaireSubmissionStatus.SUBMITTED);
        createAnswer(readJob.getId(), 103L, QuestionnaireSubmissionStatus.REVIEWED);
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setKeyword("alpha");

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(readQuery);

        assertThat(readJobs.getList()).singleElement()
                .extracting(JobPostResponse::getApplicationCount)
                .isEqualTo(2L);
    }

    @Test
    void hideExpiredJobs() {
        readJobService.lambdaUpdate()
                .eq(JobPostEntity::getPositionName, "alpha")
                .set(JobPostEntity::getApplicationDeadline, LocalDate.now().minusDays(1))
                .update();
        readJobService.lambdaUpdate()
                .eq(JobPostEntity::getPositionName, "beta")
                .set(JobPostEntity::getWorkEndDate, LocalDate.now().minusDays(1))
                .update();

        PageResult<JobPostResponse> readJobs = readJobService.listPublishedJobPosts(
                new JobPostQueryRequest());

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactly("delta", "gamma");
    }

    @Test
    void filterInternalApply() {
        readJobService.lambdaUpdate()
                .eq(JobPostEntity::getPositionName, "beta")
                .set(JobPostEntity::getSourceUrl, "https://example.test/apply")
                .update();
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setInternalApply(true);

        PageResult<JobPostResponse> readJobs = readJobService.listJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactlyInAnyOrder("alpha", "gamma", "delta");
    }

    private void createJob(String createName, String createProvince, String createCity,
                           int createSalaryMin, int createSalaryMax, LocalDate createDeadline,
                           LocalDateTime createTime) {
        JobPostEntity createJob = new JobPostEntity();
        createJob.setCompanyName("query-company");
        createJob.setPositionName(createName);
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(JobPostStatus.PUBLISHED);
        createJob.setWorkProvince(createProvince);
        createJob.setWorkCity(createCity);
        createJob.setSalaryMin(createSalaryMin);
        createJob.setSalaryMax(createSalaryMax);
        createJob.setWorkEndDate(LocalDate.now().plusDays(30));
        createJob.setApplicationDeadline(createDeadline);
        createJob.setCreatedAt(createTime);
        readJobService.save(createJob);
    }

    private void createAnswer(Long createJobId, Long createUserId,
                              QuestionnaireSubmissionStatus createStatus) {
        QuestionnaireAnswerEntity createAnswer = new QuestionnaireAnswerEntity();
        createAnswer.setJobPostId(createJobId);
        createAnswer.setUserId(createUserId);
        createAnswer.setAnswers("[]");
        createAnswer.setSubmissionStatus(createStatus);
        readAnswerService.save(createAnswer);
    }
}
