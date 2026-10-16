package com.fusioncareer;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.QuestionnaireSubmitRequest;
import com.fusioncareer.dto.res.JobPostAdminResponse;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobPostQueryTest {

    @Autowired
    private JobPostService readJobService;

    @Autowired
    private JobPostQuestionService readQuestionService;

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
    void persistAndFilterMultipleCitiesAndEducationLevels() {
        JobPostRequest createJob = new JobPostRequest();
        createJob.setCompanyName("multi-company");
        createJob.setPositionName("multi-position");
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(JobPostStatus.PUBLISHED);
        createJob.setWorkCities(List.of("上海", "北京"));
        createJob.setReqEduLevels(List.of(EduLevel.UNDERGRADUATE, EduLevel.ACADEMIC_MASTER));

        JobPostResponse readCreated = readJobService.createJobPost(createJob);

        assertThat(readCreated.getWorkCities()).containsExactly("上海", "北京");
        assertThat(readCreated.getWorkCity()).isEqualTo("上海");
        assertThat(readCreated.getReqEduLevels())
                .containsExactly(EduLevel.UNDERGRADUATE, EduLevel.ACADEMIC_MASTER);
        JobPostQueryRequest readCity = new JobPostQueryRequest();
        readCity.setWorkCity("北京");
        assertThat(readJobService.listPublishedJobPosts(readCity).getList())
                .extracting(JobPostResponse::getPositionName).contains("multi-position");
        JobPostQueryRequest readEducation = new JobPostQueryRequest();
        readEducation.setReqEduLevel(EduLevel.ACADEMIC_MASTER);
        assertThat(readJobService.listPublishedJobPosts(readEducation).getList())
                .extracting(JobPostResponse::getPositionName).contains("multi-position");
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
    void missingApplicationDeadlineRemainsUnknown() {
        JobPostRequest createJob = new JobPostRequest();
        createJob.setCompanyName("deadline-company");
        createJob.setPositionName("deadline-position");
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(JobPostStatus.OFFLINE);

        JobPostResponse readCreated = readJobService.createJobPost(createJob);

        assertThat(readCreated.getApplicationDeadline()).isNull();
        assertThat(readQuestionService.listByJobPostId(readCreated.getId())).singleElement()
                .satisfies(readQuestion -> {
                    assertThat(readQuestion.getId()).isZero();
                    assertThat(readQuestion.getTitle()).isEqualTo("个人简历");
                    assertThat(readQuestion.getRequired()).isTrue();
                });
    }

    @Test
    void hideUnavailableJob() {
        JobPostRequest createOffline = new JobPostRequest();
        createOffline.setCompanyName("hidden-company");
        createOffline.setPositionName("hidden-position");
        createOffline.setJobCategory(JobCategory.MEDIA);
        createOffline.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createOffline.setStatus(JobPostStatus.OFFLINE);
        JobPostResponse readOffline = readJobService.createJobPost(createOffline);

        assertThat(readJobService.getVisibleJobPost(readOffline.getId())).isNull();
        QuestionnaireSubmitRequest createDraft = new QuestionnaireSubmitRequest();
        createDraft.setJobPostId(readOffline.getId());
        createDraft.setAnswers("[]");
        assertThatThrownBy(() -> readAnswerService.saveDraft(991L, createDraft))
                .hasMessageContaining("岗位不存在");

        JobPostRequest createExpired = new JobPostRequest();
        createExpired.setCompanyName("expired-company");
        createExpired.setPositionName("expired-position");
        createExpired.setJobCategory(JobCategory.MEDIA);
        createExpired.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createExpired.setStatus(JobPostStatus.PUBLISHED);
        createExpired.setApplicationDeadline(LocalDate.now().minusDays(1));
        JobPostResponse readExpired = readJobService.createJobPost(createExpired);

        assertThat(readJobService.getVisibleJobPost(readExpired.getId())).isNull();
    }

    @Test
    void countApplications() {
        JobPostEntity readJob = readJobService.lambdaQuery()
                .eq(JobPostEntity::getPositionName, "alpha")
                .one();
        createAnswer(readJob.getId(), 101L, QuestionnaireSubmissionStatus.DRAFT);
        createAnswer(readJob.getId(), 102L, QuestionnaireSubmissionStatus.SUBMITTED);
        createAnswer(readJob.getId(), 103L, QuestionnaireSubmissionStatus.REVIEWED);
        createAnswer(readJob.getId(), 104L, QuestionnaireSubmissionStatus.WITHDRAWN);
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
        readQuery.setKeyword("query-company");

        PageResult<JobPostResponse> readJobs = readJobService.listJobPosts(readQuery);

        assertThat(readJobs.getList()).extracting(JobPostResponse::getPositionName)
                .containsExactlyInAnyOrder("alpha", "gamma", "delta");
    }

    @Test
    void recycleAndRestoreJob() {
        JobPostEntity readAlpha = readJobService.lambdaQuery()
                .eq(JobPostEntity::getPositionName, "alpha")
                .one();

        readJobService.recycleJob(readAlpha.getId(), "初筛未通过：测试原因");

        assertThat(readJobService.listJobPosts(new JobPostQueryRequest()).getList())
                .extracting(JobPostResponse::getPositionName)
                .doesNotContain("alpha");
        JobPostQueryRequest readRecycleQuery = new JobPostQueryRequest();
        readRecycleQuery.setStatus(JobPostStatus.RECYCLED);
        assertThat(readJobService.listJobPosts(readRecycleQuery).getList()).singleElement()
                .satisfies(readJob -> {
                    assertThat(readJob.getPositionName()).isEqualTo("alpha");
                    assertThat(readJob.getRecycleReason()).isEqualTo("初筛未通过：测试原因");
                    assertThat(readJob.getRecycledAt()).isNotNull();
                });

        readJobService.restoreJob(readAlpha.getId());
        JobPostEntity readRestored = readJobService.getById(readAlpha.getId());
        assertThat(readRestored.getStatus()).isEqualTo(JobPostStatus.OFFLINE);
        assertThat(readRestored.getRecycleReason()).isNull();
        assertThat(readRestored.getRecycledAt()).isNull();
    }

    @Test
    void adminFullTextSearchesDraftBodyRemarkAndSourceWithoutOtherStatuses() {
        JobPostEntity createBody = createSearchJob("body-draft", JobPostStatus.OFFLINE);
        createBody.setJobDesc("负责校园调研采访和报道");
        JobPostEntity createRemark = createSearchJob("remark-draft", JobPostStatus.OFFLINE);
        createRemark.setInternalRemark("校园调研：等待老师确认");
        JobPostEntity createSource = createSearchJob("source-draft", JobPostStatus.OFFLINE);
        createSource.setSourceUrl("https://example.test/校园调研/source");
        JobPostEntity createPublished = createSearchJob("body-published", JobPostStatus.PUBLISHED);
        createPublished.setJobDesc("校园调研");
        JobPostEntity createRecycled = createSearchJob("source-recycled", JobPostStatus.RECYCLED);
        createRecycled.setSourceUrl("https://example.test/校园调研/recycled");
        readJobService.saveBatch(List.of(createBody, createRemark, createSource,
                createPublished, createRecycled));

        PageResult<JobPostAdminResponse> readResult = readJobService.listAdminJobPosts(
                draftFullTextQuery("校园调研"));

        assertThat(readResult.getTotal()).isEqualTo(3);
        assertThat(readResult.getList()).extracting(JobPostAdminResponse::getPositionName)
                .containsExactlyInAnyOrder("body-draft", "remark-draft", "source-draft");
    }

    @Test
    void adminFullTextUsesTrimmedCompletePhraseAndCombinesExistingFilters() {
        JobPostEntity createMatch = createSearchJob("phrase-draft", JobPostStatus.OFFLINE);
        createMatch.setReqSkills("熟悉数据 新闻制作");
        createMatch.setWorkCity("上海");
        JobPostEntity createSeparated = createSearchJob("separated-draft", JobPostStatus.OFFLINE);
        createSeparated.setReqSkills("熟悉数据采集和新闻制作");
        createSeparated.setWorkCity("上海");
        JobPostEntity createOtherCategory = createSearchJob("category-draft", JobPostStatus.OFFLINE);
        createOtherCategory.setReqSkills("熟悉数据 新闻制作");
        createOtherCategory.setWorkCity("上海");
        createOtherCategory.setJobCategory(JobCategory.ENTERPRISE);
        JobPostEntity createOtherCity = createSearchJob("city-draft", JobPostStatus.OFFLINE);
        createOtherCity.setReqSkills("熟悉数据 新闻制作");
        createOtherCity.setWorkCity("北京");
        readJobService.saveBatch(List.of(createMatch, createSeparated, createOtherCategory, createOtherCity));
        JobPostQueryRequest readQuery = draftFullTextQuery("  数据 新闻  ");
        readQuery.setJobCategory(JobCategory.MEDIA);
        readQuery.setWorkCity("上海");

        assertThat(readJobService.listAdminJobPosts(readQuery).getList())
                .extracting(JobPostAdminResponse::getPositionName).containsExactly("phrase-draft");
    }

    @Test
    void adminFullTextPreservesPagingAndFilteredTotal() {
        for (int createIndex = 0; createIndex < 3; createIndex++) {
            JobPostEntity createMatch = createSearchJob("paged-draft-" + createIndex, JobPostStatus.OFFLINE);
            createMatch.setJobDesc("短视频拍摄");
            createMatch.setCreatedAt(LocalDateTime.of(2026, 8, 5, 8, createIndex));
            readJobService.save(createMatch);
        }
        readJobService.save(createSearchJob("unmatched-draft", JobPostStatus.OFFLINE));
        JobPostQueryRequest readQuery = draftFullTextQuery("短视频");
        readQuery.setSize(2);

        PageResult<JobPostAdminResponse> readFirst = readJobService.listAdminJobPosts(readQuery);
        readQuery.setPage(2);
        PageResult<JobPostAdminResponse> readSecond = readJobService.listAdminJobPosts(readQuery);

        assertThat(readFirst.getTotal()).isEqualTo(3);
        assertThat(readFirst.getList()).extracting(JobPostAdminResponse::getPositionName)
                .containsExactly("paged-draft-2", "paged-draft-1");
        assertThat(readSecond.getTotal()).isEqualTo(3);
        assertThat(readSecond.getList()).extracting(JobPostAdminResponse::getPositionName)
                .containsExactly("paged-draft-0");
    }

    @Test
    void adminFullTextPagingUsesIdToBreakEqualCreationTimeTies() {
        for (long createId = 81001; createId <= 81003; createId++) {
            JobPostEntity createMatch = createSearchJob("tied-draft-" + createId, JobPostStatus.OFFLINE);
            createMatch.setId(createId);
            createMatch.setJobDesc("同批导入岗位");
            readJobService.save(createMatch);
        }
        JobPostQueryRequest readQuery = draftFullTextQuery("同批导入");
        readQuery.setSize(1);

        for (int readPage = 1; readPage <= 3; readPage++) {
            readQuery.setPage(readPage);
            PageResult<JobPostAdminResponse> readResult = readJobService.listAdminJobPosts(readQuery);
            assertThat(readResult.getTotal()).isEqualTo(3);
            assertThat(readResult.getList()).extracting(JobPostAdminResponse::getId)
                    .containsExactly(81004L - readPage);
        }
    }

    @Test
    void adminFullTextBlankKeywordListsDraftsNormally() {
        readJobService.saveBatch(List.of(createSearchJob("blank-draft-1", JobPostStatus.OFFLINE),
                createSearchJob("blank-draft-2", JobPostStatus.OFFLINE)));

        for (String readKeyword : new String[] {null, "", "  \t  "}) {
            PageResult<JobPostAdminResponse> readResult = readJobService.listAdminJobPosts(
                    draftFullTextQuery(readKeyword));
            assertThat(readResult.getTotal()).isEqualTo(2);
            assertThat(readResult.getList()).extracting(JobPostAdminResponse::getPositionName)
                    .containsExactlyInAnyOrder("blank-draft-1", "blank-draft-2");
        }
    }

    @Test
    void fullTextRequiresAdminOptInAndPublicQueriesNeverSearchPrivateFields() {
        JobPostEntity createDraft = createSearchJob("default-draft", JobPostStatus.OFFLINE);
        createDraft.setJobDesc("深度采访");
        createDraft.setInternalRemark("秘密审核意见");
        JobPostEntity createPublished = createSearchJob("default-published", JobPostStatus.PUBLISHED);
        createPublished.setJobDesc("深度采访");
        createPublished.setInternalRemark("秘密审核意见");
        readJobService.saveBatch(List.of(createDraft, createPublished));
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setKeyword("深度采访");

        assertThat(readQuery.getFullText()).isFalse();
        assertThat(readJobService.listAdminJobPosts(readQuery).getTotal()).isZero();
        readQuery.setFullText(true);
        assertThat(readJobService.listAdminJobPosts(readQuery).getTotal()).isEqualTo(2);
        assertThat(readJobService.listJobPosts(readQuery).getTotal()).isZero();
        assertThat(readJobService.listJobs(readQuery).getTotal()).isZero();
        assertThat(readJobService.listPublishedJobPosts(readQuery).getTotal()).isZero();
        readQuery.setKeyword("秘密审核意见");
        assertThat(readJobService.listPublishedJobPosts(readQuery).getTotal()).isZero();
        readQuery.setFullText(null);
        assertThat(readJobService.listAdminJobPosts(readQuery).getTotal()).isZero();
        readQuery.setKeyword("default-draft");
        assertThat(readJobService.listAdminJobPosts(readQuery).getList())
                .extracting(JobPostAdminResponse::getPositionName).containsExactly("default-draft");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100%", "path_name", "C:\\", "news!desk", "%' OR 1=1 --"})
    void adminFullTextTreatsWildcardsEscapesAndSqlAsLiteralText(String readKeyword) {
        JobPostEntity createLiteral = createSearchJob("literal-draft", JobPostStatus.OFFLINE);
        createLiteral.setJobDesc("prefix " + readKeyword + " suffix");
        JobPostEntity createOther = createSearchJob("different-draft", JobPostStatus.OFFLINE);
        createOther.setJobDesc("100 dollars, pathXname, C:folder, newsdesk");
        readJobService.saveBatch(List.of(createLiteral, createOther));

        assertThat(readJobService.listAdminJobPosts(draftFullTextQuery(readKeyword)).getList())
                .extracting(JobPostAdminResponse::getPositionName).containsExactly("literal-draft");
    }

    private JobPostQueryRequest draftFullTextQuery(String readKeyword) {
        JobPostQueryRequest readQuery = new JobPostQueryRequest();
        readQuery.setStatus(JobPostStatus.OFFLINE);
        readQuery.setFullText(true);
        readQuery.setKeyword(readKeyword);
        return readQuery;
    }

    private JobPostEntity createSearchJob(String createName, JobPostStatus createStatus) {
        JobPostEntity createJob = new JobPostEntity();
        createJob.setCompanyName("search-company");
        createJob.setPositionName(createName);
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(createStatus);
        createJob.setApplicationDeadline(LocalDate.now().plusDays(30));
        createJob.setCreatedAt(LocalDateTime.of(2026, 8, 5, 8, 0));
        return createJob;
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
