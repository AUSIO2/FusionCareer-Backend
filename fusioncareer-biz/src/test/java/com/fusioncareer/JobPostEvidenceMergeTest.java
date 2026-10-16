package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.dto.req.JobPostEvidenceMergeRequest;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobRecycleRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.res.JobPostAdminResponse;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.SourceType;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.JobPostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobPostEvidenceMergeTest {

    @Autowired
    private JobPostService readJobService;

    @Autowired
    private ObjectMapper readObjectMapper;

    @Test
    void mergeEvidenceIsMonotonicAtomicAndIdempotent() throws Exception {
        JobPostResponse readKeeper = createKeeper();

        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setCompanyName("慧策集团");
        readIncoming.setPositionName("培训管培生");
        readIncoming.setWorkCities(List.of("北京", "天津"));
        readIncoming.setApplicationDeadline(LocalDate.of(2026, 10, 30));
        readIncoming.setSalaryDisplay("基本工资+绩效工资；五险一金、餐补");
        readIncoming.setReqGradYear("2027届");
        readIncoming.setReqOther("网申：https://huicecom.zhiye.com/Campus；邮箱：xzhr@huice.com");
        readIncoming.setWorkTimeRequirement("每周至少到岗三天");
        readIncoming.setCareerDirection("企业培训与人才发展");
        readIncoming.setInternalCompensation("补贴信息仅内部可见");
        readIncoming.setContactName("招聘负责人");
        readIncoming.setContactInfo("xzhr@huice.com");
        readIncoming.setInternalRemark("来自高校转载页");
        readIncoming.setRecommended(true);

        JobPostEvidenceMergeRequest updateRequest = mergeRequest(readKeeper, readIncoming);
        JobPostAdminResponse readMerged = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), updateRequest);
        assertThat(readMerged.getUpdatedAt()).isAfter(readKeeper.getUpdatedAt());

        assertThat(readMerged.getCompanyName()).isEqualTo("北京掌上先机网络科技有限公司");
        assertThat(readMerged.getPositionName()).isEqualTo("培训管培生");
        assertThat(readMerged.getSourceUrl()).isEqualTo("https://career.example.edu/keeper");
        assertThat(readMerged.getStatus()).isEqualTo(JobPostStatus.PUBLISHED);
        assertThat(readMerged.getWorkCities()).containsExactly("北京", "天津");
        assertThat(readMerged.getApplicationDeadline()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(readMerged.getSalaryDisplay()).isEqualTo("基本工资+绩效工资；五险一金、餐补");
        assertThat(readMerged.getReqGradYear()).isEqualTo("2027届");
        assertThat(readMerged.getReqOther()).contains("hr@huice.com", "xzhr@huice.com");
        assertThat(readMerged.getRecommended()).isTrue();
        assertThat(readMerged.getWorkTimeRequirement()).isEqualTo("每周至少到岗三天");
        assertThat(readMerged.getCareerDirection()).isEqualTo("企业培训与人才发展");
        assertThat(readMerged.getInternalCompensation()).isEqualTo("补贴信息仅内部可见");
        assertThat(readMerged.getContactInfo()).isEqualTo("xzhr@huice.com");
        assertThat(readMerged.getInternalRemark()).isEqualTo("来自高校转载页");

        List<JobPostEntity> readEvidence = evidenceRows(readKeeper.getId());
        assertThat(readEvidence).hasSize(2);
        assertThat(readEvidence).extracting(JobPostEntity::getRecycleReason)
                .allMatch(readReason -> readReason.matches(
                        "dedup-evidence:v1;keeper=" + readKeeper.getId()
                                + ";role=(keeper-before|incoming);sha256=[0-9a-f]{64}"));
        JobPostEntity readKeeperBefore = readEvidence.stream()
                .filter(readJob -> readJob.getRecycleReason().contains("role=keeper-before"))
                .findFirst().orElseThrow();
        JobPostEntity readIncomingEvidence = readEvidence.stream()
                .filter(readJob -> readJob.getRecycleReason().contains("role=incoming"))
                .findFirst().orElseThrow();
        assertThat(readKeeperBefore.getWorkCities()).containsExactly("北京");
        assertThat(readKeeperBefore.getApplicationDeadline()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(readKeeperBefore.getCreatedAt()).isEqualTo(readKeeper.getCreatedAt());
        assertThat(readIncomingEvidence.getWorkCities()).containsExactly("北京", "天津");
        assertThat(readIncomingEvidence.getApplicationDeadline()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(readIncomingEvidence.getStatus()).isEqualTo(JobPostStatus.RECYCLED);
        assertThat(readIncomingEvidence.getRecommended()).isTrue();
        assertThat(readIncomingEvidence.getContactInfo()).isEqualTo("xzhr@huice.com");

        JobPostQueryRequest readRecycleBinQuery = new JobPostQueryRequest();
        readRecycleBinQuery.setStatus(JobPostStatus.RECYCLED);
        readRecycleBinQuery.setSize(20);
        assertThat(readJobService.listAdminJobPosts(readRecycleBinQuery).getList())
                .noneMatch(readJob -> readJob.getRecycleReason() != null
                        && readJob.getRecycleReason().startsWith("dedup-evidence:v1;"));
        assertThatThrownBy(() -> readJobService.restoreJob(readIncomingEvidence.getId()))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(readJobService.getById(readIncomingEvidence.getId()).getStatus())
                .isEqualTo(JobPostStatus.RECYCLED);

        // A stale retry must refresh even if the current row already contains
        // the same evidence; otherwise a changed identity could be swallowed.
        updateRequest.setExpectedUpdatedAt(readKeeper.getUpdatedAt());
        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), updateRequest))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));

        // After refresh, applying the same evidence is an idempotent no-op.
        updateRequest.setExpectedUpdatedAt(readMerged.getUpdatedAt());
        JobPostAdminResponse readRetried = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), updateRequest);
        assertThat(readRetried.getWorkCities()).containsExactly("北京", "天津");
        assertThat(count(readRetried.getReqOther(), "xzhr@huice.com")).isEqualTo(1);
        assertThat(readRetried.getUpdatedAt()).isEqualTo(readMerged.getUpdatedAt());
        assertThat(evidenceRows(readKeeper.getId())).hasSize(2);

        JobPostRequest readLaterIncoming = new JobPostRequest();
        readLaterIncoming.setCompanyName("慧策集团");
        readLaterIncoming.setPositionName("培训管培生");
        readLaterIncoming.setWorkCities(List.of("北京", "天津"));
        readLaterIncoming.setApplicationDeadline(LocalDate.of(2026, 10, 30));
        readLaterIncoming.setReqGradYear("2027届");
        readLaterIncoming.setReqOther(
                "网申：https://huicecom.zhiye.com/Campus；邮箱：campus@huice.com");
        JobPostAdminResponse readMergedAgain = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readRetried, readLaterIncoming));
        List<JobPostEntity> readLaterEvidence = evidenceRows(readKeeper.getId());
        assertThat(readLaterEvidence).hasSize(3);
        assertThat(readLaterEvidence.stream().filter(readJob ->
                readJob.getRecycleReason().contains("role=keeper-before")).count()).isEqualTo(1);
        assertThat(readLaterEvidence.stream().filter(readJob ->
                readJob.getRecycleReason().contains("role=incoming")).count()).isEqualTo(2);
        assertThat(readMergedAgain.getUpdatedAt()).isAfter(readRetried.getUpdatedAt());

        JobPostRequest updateAdmin = new JobPostRequest();
        updateAdmin.setExpectedUpdatedAt(readMergedAgain.getUpdatedAt());
        updateAdmin.setDepartment("人才发展部");
        readJobService.updateJobPost(readKeeper.getId(), updateAdmin);
        JobPostResponse readAdminUpdated = readJobService.getJobPost(readKeeper.getId());
        assertThat(readAdminUpdated.getDepartment()).isEqualTo("人才发展部");
        assertThat(readAdminUpdated.getUpdatedAt()).isAfter(readMergedAgain.getUpdatedAt());

        JobPostQueryRequest readInternalQuery = new JobPostQueryRequest();
        readInternalQuery.setSize(20);
        JobPostAdminResponse readInternalKeeper = readJobService.listJobs(readInternalQuery)
                .getList().stream().filter(readJob -> readJob.getId().equals(readKeeper.getId()))
                .findFirst().orElseThrow();
        assertThat(readInternalKeeper.getContactInfo()).isEqualTo("xzhr@huice.com");
        assertThat(readInternalKeeper.getWorkTimeRequirement()).isEqualTo("每周至少到岗三天");
        assertThat(readInternalKeeper.getCareerDirection()).isEqualTo("企业培训与人才发展");

        JobPostQueryRequest readPublicQuery = new JobPostQueryRequest();
        readPublicQuery.setSize(20);
        JobPostResponse readPublicKeeper = readJobService.listPublishedJobPosts(readPublicQuery)
                .getList().stream().filter(readJob -> readJob.getId().equals(readKeeper.getId()))
                .findFirst().orElseThrow();
        String readPublicJson = readObjectMapper.writeValueAsString(readPublicKeeper);
        assertThat(readPublicJson).doesNotContain(
                "contactInfo", "internalCompensation", "internalRemark",
                "workTimeRequirement", "careerDirection");
    }

    @Test
    void staleVersionWithUnmergedEvidenceReturnsConflict() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setApplicationDeadline(LocalDate.of(2026, 10, 30));

        JobPostEvidenceMergeRequest updateRequest = mergeRequest(readKeeper, readIncoming);
        updateRequest.setExpectedUpdatedAt(readKeeper.getUpdatedAt().minusSeconds(1));

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), updateRequest))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
    }

    @Test
    void newNoopEvidenceAdvancesVersionAndSerializesSameSnapshotRequests() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest readFirstIncoming = new JobPostRequest();
        readFirstIncoming.setCompanyName("北京掌上先机网络科技有限公司");
        readFirstIncoming.setPositionName("培训管培生");
        JobPostAdminResponse readFirst = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readFirstIncoming));

        assertThat(readFirst.getUpdatedAt()).isAfter(readKeeper.getUpdatedAt());
        assertThat(evidenceRows(readKeeper.getId())).hasSize(2);

        JobPostRequest readSecondIncoming = new JobPostRequest();
        readSecondIncoming.setCompanyName("慧策集团");
        readSecondIncoming.setPositionName("培训管培生");
        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readSecondIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(evidenceRows(readKeeper.getId())).hasSize(2);

        JobPostAdminResponse readSecond = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readFirst, readSecondIncoming));
        assertThat(readSecond.getUpdatedAt()).isAfter(readFirst.getUpdatedAt());
        assertThat(evidenceRows(readKeeper.getId())).hasSize(3);
    }

    @Test
    void administrativePutRequiresFreshVersionAfterEvidenceMerge() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest updateOriginal = new JobPostRequest();
        updateOriginal.setJobDesc("原始职责 A");
        readJobService.updateJobPost(readKeeper.getId(), updateOriginal);
        JobPostResponse readVersionA = readJobService.getJobPost(readKeeper.getId());

        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setJobDesc("新增职责 B");
        JobPostAdminResponse readMerged = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readVersionA, readIncoming));
        assertThat(readMerged.getJobDesc()).contains("原始职责 A", "新增职责 B");

        JobPostRequest updateStale = new JobPostRequest();
        updateStale.setExpectedUpdatedAt(readVersionA.getUpdatedAt());
        updateStale.setJobDesc("旧页面覆盖");
        assertThatThrownBy(() -> readJobService.updateJobPost(readKeeper.getId(), updateStale))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));

        JobPostRequest updateWithoutVersion = new JobPostRequest();
        updateWithoutVersion.setJobDesc("无版本覆盖");
        assertThatThrownBy(() -> readJobService.updateJobPost(
                readKeeper.getId(), updateWithoutVersion))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(readJobService.getJobPost(readKeeper.getId()).getJobDesc())
                .contains("原始职责 A", "新增职责 B");

        JobPostRequest updateFresh = new JobPostRequest();
        updateFresh.setExpectedUpdatedAt(readMerged.getUpdatedAt());
        updateFresh.setJobDesc("管理员确认后的职责");
        readJobService.updateJobPost(readKeeper.getId(), updateFresh);
        JobPostResponse readUpdated = readJobService.getJobPost(readKeeper.getId());
        assertThat(readUpdated.getJobDesc()).isEqualTo("管理员确认后的职责");
        assertThat(readUpdated.getUpdatedAt()).isAfter(readMerged.getUpdatedAt());
    }

    @Test
    void conflictingCityAndEducationAreNeverMerged() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setWorkCities(List.of("上海"));
        readIncoming.setReqEduLevels(List.of(EduLevel.DOCTORAL));

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));

        JobPostEntity readUnchanged = readJobService.getById(readKeeper.getId());
        assertThat(readUnchanged.getWorkCities()).containsExactly("北京");
        assertThat(readUnchanged.getReqEduLevels()).containsExactly(EduLevel.UNDERGRADUATE);
    }

    @Test
    void missingMajorAndGenericGraduationTextUpgradeWithoutConflict() {
        JobPostResponse readKeeper = createKeeper(null, "应届生");
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setReqMajor("计算机类");
        readIncoming.setReqGradYear("应届生+实习生");

        JobPostResponse readMerged = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming));

        assertThat(readMerged.getReqMajor()).isEqualTo("计算机类");
        assertThat(readMerged.getReqGradYear()).isEqualTo("应届生+实习生");

        JobPostRequest readUnrestrictedRepost = new JobPostRequest();
        readUnrestrictedRepost.setReqMajor("不限专业");
        readUnrestrictedRepost.setReqGradYear("应届生");
        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readMerged.getId(), mergeRequest(readMerged, readUnrestrictedRepost)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
    }

    @Test
    void graduationYearMergePreservesExplicitAndGenericTextInBothDirections() {
        JobPostResponse readExplicitKeeper = createKeeper(null, "2027届");
        JobPostRequest readGenericIncoming = new JobPostRequest();
        readGenericIncoming.setReqGradYear("毕业两年内");
        JobPostAdminResponse readFirstMerged = readJobService.mergeJobPostEvidence(
                readExplicitKeeper.getId(), mergeRequest(readExplicitKeeper, readGenericIncoming));
        assertThat(readFirstMerged.getReqGradYear()).isEqualTo("2027届、毕业两年内");

        JobPostResponse readGenericKeeper = createKeeper(null, "毕业两年内");
        JobPostRequest readExplicitIncoming = new JobPostRequest();
        readExplicitIncoming.setReqGradYear("2027届");
        JobPostAdminResponse readSecondMerged = readJobService.mergeJobPostEvidence(
                readGenericKeeper.getId(), mergeRequest(readGenericKeeper, readExplicitIncoming));
        assertThat(readSecondMerged.getReqGradYear()).isEqualTo("毕业两年内、2027届");
    }

    @Test
    void explicitDeadlineReplacesLegacyCreatedAtPlusOneMonthDefault() {
        JobPostResponse readKeeper = createKeeper();
        LocalDate readLegacyDefault = readKeeper.getCreatedAt().toLocalDate().plusMonths(1);
        JobPostRequest updateLegacy = new JobPostRequest();
        updateLegacy.setApplicationDeadline(readLegacyDefault);
        readJobService.updateJobPost(readKeeper.getId(), updateLegacy);
        JobPostResponse readLegacyKeeper = readJobService.getJobPost(readKeeper.getId());

        LocalDate readExplicitEarlier = readKeeper.getCreatedAt().toLocalDate().plusDays(10);
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setApplicationDeadline(readExplicitEarlier);
        JobPostAdminResponse readMerged = readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readLegacyKeeper, readIncoming));

        assertThat(readExplicitEarlier).isBefore(readLegacyDefault);
        assertThat(readMerged.getApplicationDeadline()).isEqualTo(readExplicitEarlier);
    }

    @ParameterizedTest
    @CsvSource({
            "非应届生,应届生",
            "应届生,往届生",
            "非应届实习生,应届实习生"
    })
    void freshAndNonFreshGraduateRequirementsConflict(
            String readKeeperRequirement, String readIncomingRequirement) {
        JobPostResponse readKeeper = createKeeper(null, readKeeperRequirement);
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setReqGradYear(readIncomingRequirement);

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void semanticallyDifferentPositionNameIsRejected() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setPositionName("C# 开发工程师");

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
    }

    @Test
    void semanticProgrammingSymbolsRemainDistinctInMajorRequirements() {
        JobPostResponse readKeeper = createKeeper("C++", null);
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setReqMajor("C#");

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void unrestrictedMajorIsExplicitAndConflictsWithSpecificMajor() {
        JobPostResponse readKeeper = createKeeper("不限专业", null);
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setReqMajor("计算机类");

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void explicitExternalJobIdentifiersCannotBeMergedAcrossDifferentJobs() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest updateKeeper = new JobPostRequest();
        updateKeeper.setReqOther("投递说明；职位ID：123456");
        readJobService.updateJobPost(readKeeper.getId(), updateKeeper);
        JobPostResponse readVersionedKeeper = readJobService.getJobPost(readKeeper.getId());

        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setReqOther("投递说明；Job Code: ABC-987654");
        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readVersionedKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void alphabeticExternalJobIdentifiersRemainStrongEvidence() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest updateKeeper = new JobPostRequest();
        updateKeeper.setJobDesc("招聘代码：ABCDEF");
        readJobService.updateJobPost(readKeeper.getId(), updateKeeper);
        JobPostResponse readVersionedKeeper = readJobService.getJobPost(readKeeper.getId());

        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setJobDesc("Position No. MKTG");
        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readVersionedKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void recycledIncomingCannotPolluteActiveKeeperOrCreateEvidence() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setStatus(JobPostStatus.RECYCLED);
        readIncoming.setApplicationDeadline(LocalDate.of(2026, 12, 31));
        readIncoming.setContactInfo("should-not-merge@example.com");

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), mergeRequest(readKeeper, readIncoming)))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(409));

        JobPostEntity readUnchanged = readJobService.getById(readKeeper.getId());
        assertThat(readUnchanged.getApplicationDeadline())
                .isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(readUnchanged.getContactInfo()).isNull();
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void missingRequiredIncomingEvidenceIsRejectedInsteadOfFilledFromKeeper() {
        JobPostResponse readKeeper = createKeeper();
        JobPostRequest readIncoming = new JobPostRequest();
        readIncoming.setSourceType(SourceType.CRAWL);
        readIncoming.setCompanyName("慧策集团");
        readIncoming.setPositionName("培训管培生");
        readIncoming.setJobCategory(JobCategory.ENTERPRISE);
        // recruitType deliberately remains absent: absence must not be turned
        // into the keeper's recruiting-family anchor.
        JobPostEvidenceMergeRequest updateRequest = new JobPostEvidenceMergeRequest();
        updateRequest.setExpectedUpdatedAt(readKeeper.getUpdatedAt());
        updateRequest.setIncoming(readIncoming);

        assertThatThrownBy(() -> readJobService.mergeJobPostEvidence(
                readKeeper.getId(), updateRequest))
                .isInstanceOfSatisfying(ServiceException.class,
                        readError -> assertThat(readError.getCode()).isEqualTo(400));
        assertThat(evidenceRows(readKeeper.getId())).isEmpty();
    }

    @Test
    void recycleBatchAndRestoreKeepVersionsMonotonic() {
        JobPostResponse readFirst = createKeeper();
        readJobService.recycleJob(readFirst.getId(), "测试回收");
        JobPostEntity readRecycled = readJobService.getById(readFirst.getId());
        assertThat(readRecycled.getUpdatedAt()).isAfter(readFirst.getUpdatedAt());

        readJobService.restoreJob(readFirst.getId());
        JobPostEntity readRestored = readJobService.getById(readFirst.getId());
        assertThat(readRestored.getStatus()).isEqualTo(JobPostStatus.OFFLINE);
        assertThat(readRestored.getUpdatedAt()).isAfter(readRecycled.getUpdatedAt());

        JobPostResponse readSecond = createKeeper();
        JobPostResponse readThird = createKeeper();
        JobRecycleRequest updateSecond = new JobRecycleRequest();
        updateSecond.setId(readSecond.getId());
        updateSecond.setReason("批量回收二");
        JobRecycleRequest updateThird = new JobRecycleRequest();
        updateThird.setId(readThird.getId());
        updateThird.setReason("批量回收三");
        readJobService.recycleJobs(List.of(updateThird, updateSecond));

        assertThat(readJobService.getById(readSecond.getId()).getUpdatedAt())
                .isAfter(readSecond.getUpdatedAt());
        assertThat(readJobService.getById(readThird.getId()).getUpdatedAt())
                .isAfter(readThird.getUpdatedAt());
    }

    private JobPostResponse createKeeper() {
        return createKeeper(null, null);
    }

    private JobPostResponse createKeeper(String readMajor, String readGradYear) {
        JobPostRequest createJob = new JobPostRequest();
        createJob.setSourceType(SourceType.CRAWL);
        createJob.setSourceUrl("https://career.example.edu/keeper");
        createJob.setCompanyName("北京掌上先机网络科技有限公司");
        createJob.setPositionName("培训管培生");
        createJob.setJobCategory(JobCategory.ENTERPRISE);
        createJob.setRecruitType(RecruitType.CAMPUS_RECRUITMENT);
        createJob.setWorkCities(List.of("北京"));
        createJob.setReqEduLevels(List.of(EduLevel.UNDERGRADUATE));
        createJob.setReqMajor(readMajor);
        createJob.setReqGradYear(readGradYear);
        createJob.setApplicationDeadline(LocalDate.of(2026, 10, 16));
        createJob.setReqOther("网申：https://huicecom.zhiye.com/Campus；邮箱：hr@huice.com");
        createJob.setRecommended(false);
        createJob.setStatus(JobPostStatus.PUBLISHED);
        Long readId = readJobService.createJobPost(createJob).getId();
        return readJobService.getJobPost(readId);
    }

    private JobPostEvidenceMergeRequest mergeRequest(
            JobPostResponse readKeeper, JobPostRequest readIncoming) {
        if (readIncoming.getSourceType() == null) {
            readIncoming.setSourceType(readKeeper.getSourceType());
        }
        if (readIncoming.getCompanyName() == null) {
            readIncoming.setCompanyName(readKeeper.getCompanyName());
        }
        if (readIncoming.getPositionName() == null) {
            readIncoming.setPositionName(readKeeper.getPositionName());
        }
        if (readIncoming.getJobCategory() == null) {
            readIncoming.setJobCategory(readKeeper.getJobCategory());
        }
        if (readIncoming.getRecruitType() == null) {
            readIncoming.setRecruitType(readKeeper.getRecruitType());
        }
        JobPostEvidenceMergeRequest updateRequest = new JobPostEvidenceMergeRequest();
        updateRequest.setExpectedUpdatedAt(readKeeper.getUpdatedAt());
        updateRequest.setIncoming(readIncoming);
        return updateRequest;
    }

    private int count(String readText, String readToken) {
        return (readText.length() - readText.replace(readToken, "").length()) / readToken.length();
    }

    private List<JobPostEntity> evidenceRows(Long readKeeperId) {
        return readJobService.lambdaQuery()
                .eq(JobPostEntity::getStatus, JobPostStatus.RECYCLED)
                .likeRight(JobPostEntity::getRecycleReason,
                        "dedup-evidence:v1;keeper=" + readKeeperId + ";")
                .orderByAsc(JobPostEntity::getId)
                .list();
    }
}
