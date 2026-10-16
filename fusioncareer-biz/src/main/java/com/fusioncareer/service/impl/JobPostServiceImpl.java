package com.fusioncareer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.JobPostApplicationCount;
import com.fusioncareer.dto.req.JobPostEvidenceMergeRequest;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.req.JobPostRequest;
import com.fusioncareer.dto.req.JobRecycleRequest;
import com.fusioncareer.dto.res.JobPostAdminResponse;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.JobPostMapper;
import com.fusioncareer.mapper.QuestionnaireAnswerMapper;
import com.fusioncareer.service.JobPostService;
import lombok.RequiredArgsConstructor;
import cn.hutool.core.bean.BeanUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.fusioncareer.util.PaginationUtil.createPage;

@Service
@RequiredArgsConstructor
public class JobPostServiceImpl extends ServiceImpl<JobPostMapper, JobPostEntity> implements JobPostService {

    private static final int MYSQL_TEXT_SAFE_BYTES = 60_000;
    private static final String DEDUP_EVIDENCE_VERSION = "dedup-evidence:v1";
    private static final Pattern GRADUATION_YEAR = Pattern.compile("(?<!\\d)20\\d{2}(?!\\d)");
    private static final Pattern FRESH_GRADUATE = Pattern.compile("(?<!非)应届");
    private static final Pattern EXTERNAL_JOB_ID = Pattern.compile(
            "(?:(?:职位|岗位|招聘)\\s*(?:ID|编号|编码|代码)"
                    + "|(?:Job|Position|Requisition)\\s*(?:ID|Code|No\\.?))"
                    + "\\s*(?:[:：#=]|为|是)?\\s*([\\p{L}\\p{N}._/-]{1,64})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> EXTERNAL_JOB_ID_STOPWORDS = Set.of(
            "none", "null", "unknown", "未知", "详见", "无", "暂无", "n/a");

    private static final List<String> ADMIN_FULL_TEXT_COLUMNS = List.of(
            "position_name", "company_name", "department", "job_desc",
            "req_major", "req_grad_year", "req_skills", "req_other", "career_direction",
            "work_location", "work_province", "work_city", "work_cities",
            "salary_display", "work_time_requirement", "headcount_display", "internal_compensation",
            "contact_name", "contact_info", "internal_remark", "source_url", "recycle_reason");

    private final QuestionnaireAnswerMapper readAnswerMapper;

    @Transactional
    @Override
    public JobPostResponse createJobPost(JobPostRequest request) {
        JobPostEntity entity = BeanUtil.copyProperties(request, JobPostEntity.class);
        normalizeRequirements(entity);
        prepareRecycleFields(entity);
        save(entity);
        return toResponse(entity);
    }

    @Transactional
    @Override
    public void createJobPostBatch(List<JobPostRequest> requests) {
        List<JobPostEntity> entities = requests.stream().map(req -> {
            JobPostEntity e = BeanUtil.copyProperties(req, JobPostEntity.class);
            normalizeRequirements(e);
            prepareRecycleFields(e);
            return e;
        }).toList();
        saveBatch(entities);
    }

    @Override
    public JobPostResponse getJobPost(Long id) {
        JobPostResponse readJob = toResponse(getById(id));
        if (readJob != null) {
            mapApplications(List.of(readJob));
        }
        return readJob;
    }

    @Override
    public JobPostResponse getVisibleJobPost(Long id) {
        JobPostResponse readJob = toResponse(getVisibleJob(id));
        if (readJob != null) {
            mapApplications(List.of(readJob));
        }
        return readJob;
    }

    @Override
    public JobPostEntity getVisibleJob(Long id) {
        LambdaQueryWrapper<JobPostEntity> readQuery = new LambdaQueryWrapper<JobPostEntity>()
                .eq(JobPostEntity::getId, id);
        filterVisibleJobs(readQuery);
        return getOne(readQuery);
    }

    @Override
    public JobPostAdminResponse getAdminJobPost(Long id) {
        JobPostAdminResponse readJob = toAdminResponse(getById(id));
        if (readJob != null) {
            mapApplications(List.of(readJob));
        }
        return readJob;
    }

    @Override
    public PageResult<JobPostResponse> listJobPosts(JobPostQueryRequest query) {
        LambdaQueryWrapper<JobPostEntity> readWrapper = buildJobQuery(query);
        readWrapper.ne(query.getStatus() == null,
                JobPostEntity::getStatus, JobPostStatus.RECYCLED);
        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), readWrapper);
        return mapPage(readJobs);
    }

    @Override
    public PageResult<JobPostAdminResponse> listAdminJobPosts(JobPostQueryRequest query) {
        LambdaQueryWrapper<JobPostEntity> readWrapper = buildJobQuery(query,
                Boolean.TRUE.equals(query.getFullText()));
        readWrapper.and(readJob -> readJob.isNull(JobPostEntity::getRecycleReason)
                .or().notLikeRight(JobPostEntity::getRecycleReason,
                        DEDUP_EVIDENCE_VERSION + ";"));
        readWrapper.ne(query.getStatus() == null,
                JobPostEntity::getStatus, JobPostStatus.RECYCLED);
        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), readWrapper);
        List<JobPostAdminResponse> readItems = readJobs.getRecords().stream()
                .map(this::toAdminResponse).toList();
        mapApplications(readItems);
        PageResult<JobPostAdminResponse> readPage = new PageResult<>(readJobs.getTotal(),
                (int) readJobs.getCurrent(), (int) readJobs.getSize());
        readPage.addAll(readItems);
        return readPage;
    }

    @Override
    public PageResult<JobPostAdminResponse> listJobs(JobPostQueryRequest readQuery) {
        Page<JobPostEntity> readJobs = page(
                createPage(readQuery.getPage(), readQuery.getSize()), buildJobQuery(readQuery));
        List<JobPostAdminResponse> readItems = readJobs.getRecords().stream()
                .map(this::toAdminResponse).toList();
        PageResult<JobPostAdminResponse> readPage = new PageResult<>(
                readJobs.getTotal(), (int) readJobs.getCurrent(), (int) readJobs.getSize());
        readPage.addAll(readItems);
        return readPage;
    }

    @Override
    public PageResult<JobPostResponse> listPublishedJobPosts(JobPostQueryRequest query) {
        LambdaQueryWrapper<JobPostEntity> buildQuery = buildJobQuery(query);
        filterVisibleJobs(buildQuery);

        Page<JobPostEntity> readJobs = page(
                createPage(query.getPage(), query.getSize()), buildQuery);
        return mapPage(readJobs);
    }

    private void filterVisibleJobs(LambdaQueryWrapper<JobPostEntity> updateQuery) {
        LocalDate readToday = LocalDate.now();
        updateQuery.eq(JobPostEntity::getStatus, JobPostStatus.PUBLISHED)
                .and(readJob -> readJob.isNull(JobPostEntity::getApplicationDeadline)
                        .or().ge(JobPostEntity::getApplicationDeadline, readToday))
                .and(readJob -> readJob.isNull(JobPostEntity::getWorkEndDate)
                        .or().ge(JobPostEntity::getWorkEndDate, readToday));
    }

    @Transactional
    @Override
    public void updateJobPost(Long id, JobPostRequest request) {
        JobPostEntity readCurrent = lockJob(id);
        if (request.getExpectedUpdatedAt() != null
                && !Objects.equals(request.getExpectedUpdatedAt(), readCurrent.getUpdatedAt())) {
            throw ServiceException.of(ResultCode.CONFLICT, "岗位已被其他请求更新，请刷新后重试");
        }
        if (request.getExpectedUpdatedAt() == null
                && evidenceRoleExists(id, "keeper-before")) {
            throw ServiceException.of(ResultCode.CONFLICT,
                    "该岗位已有去重合并记录，请刷新后使用版本化编辑");
        }
        JobPostEntity entity = BeanUtil.copyProperties(request, JobPostEntity.class);
        entity.setId(id);
        normalizeRequirements(entity);
        entity.setUpdatedAt(nextEvidenceVersion(readCurrent.getUpdatedAt()));
        if (!updateById(entity)) {
            throw ServiceException.of(ResultCode.CONFLICT, "岗位更新失败，请刷新后重试");
        }
    }

    /**
     * Merge evidence without replacing the canonical identity, publication
     * state, or audit fields of the persisted keeper.
     *
     * <p>The row lock closes the read/merge/write race.  The timestamp is an
     * optimistic precondition for the Agent snapshot. A stale request is
     * always rejected; the caller must refresh and re-run duplicate matching
     * before retrying with the new timestamp.</p>
     */
    @Transactional
    @Override
    public JobPostAdminResponse mergeJobPostEvidence(Long id, JobPostEvidenceMergeRequest request) {
        if (request == null || request.getExpectedUpdatedAt() == null || request.getIncoming() == null) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED,
                    "expectedUpdatedAt 和 incoming 不能为空");
        }
        JobPostEntity readKeeper = lockJob(id);
        if (readKeeper.getStatus() == JobPostStatus.RECYCLED) {
            throw ServiceException.of(ResultCode.CONFLICT, "岗位已进入回收站，不能合并新证据");
        }
        if (!Objects.equals(readKeeper.getUpdatedAt(), request.getExpectedUpdatedAt())) {
            throw ServiceException.of(ResultCode.CONFLICT, "岗位已被其他请求更新，请刷新后重试");
        }

        JobPostEntity readIncoming = BeanUtil.copyProperties(
                request.getIncoming(), JobPostEntity.class);
        normalizeRequirements(readIncoming);
        validateIncomingEvidence(readIncoming);
        if (readIncoming.getStatus() == JobPostStatus.RECYCLED) {
            throw ServiceException.of(ResultCode.CONFLICT,
                    "回收站岗位不能作为 active keeper 的合并证据");
        }
        if (StringUtils.hasText(readIncoming.getPositionName())
                && !normalizePositionName(readKeeper.getPositionName()).equals(
                        normalizePositionName(readIncoming.getPositionName()))) {
            throw ServiceException.of(ResultCode.CONFLICT, "岗位名称存在冲突");
        }
        JobPostEntity updateMerged = mergeCompatibleEvidence(readKeeper, readIncoming);
        boolean readChanged = mergeFieldsChanged(readKeeper, updateMerged);

        String readIncomingMarker = evidenceMarker(
                id, "incoming", evidenceHash(readIncoming));
        boolean readIncomingRecorded = evidenceExists(readIncomingMarker);
        boolean createEvidence = false;
        if (!readIncomingRecorded) {
            if (!evidenceRoleExists(id, "keeper-before")) {
                String readKeeperMarker = evidenceMarker(
                        id, "keeper-before", evidenceHash(readKeeper));
                createEvidence |= persistEvidenceSnapshot(
                        readKeeper, readKeeperMarker, true);
            }
            createEvidence |= persistEvidenceSnapshot(
                    readIncoming, readIncomingMarker, false);
        }

        if (readChanged || createEvidence) {
            JobPostEntity updatePatch = readChanged
                    ? mergePatch(updateMerged) : new JobPostEntity();
            updatePatch.setId(id);
            updatePatch.setUpdatedAt(nextEvidenceVersion(readKeeper.getUpdatedAt()));
            if (!updateById(updatePatch)) {
                throw ServiceException.of(ResultCode.CONFLICT, "岗位合并失败，请刷新后重试");
            }
        }
        // Re-read the explicit monotonic version written above.
        return toAdminResponse(baseMapper.selectById(id));
    }

    @Transactional
    @Override
    public void recycleJob(Long id, String reason) {
        JobPostEntity readCurrent = lockJob(id);
        JobPostEntity updateJob = new JobPostEntity();
        updateJob.setId(id);
        updateJob.setStatus(JobPostStatus.RECYCLED);
        updateJob.setRecommended(false);
        updateJob.setRecycleReason(cleanReason(reason));
        updateJob.setRecycledAt(LocalDateTime.now());
        updateJob.setUpdatedAt(nextEvidenceVersion(readCurrent.getUpdatedAt()));
        if (!updateById(updateJob)) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "岗位不存在");
        }
    }

    @Transactional
    @Override
    public void recycleJobs(List<JobRecycleRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        Map<Long, JobRecycleRequest> readRequests = requests.stream()
                .filter(Objects::nonNull)
                .filter(readRequest -> readRequest.getId() != null)
                .collect(Collectors.toMap(
                        JobRecycleRequest::getId,
                        readRequest -> readRequest,
                        (readEarlier, readLater) -> readLater,
                        LinkedHashMap::new));
        if (readRequests.isEmpty()) {
            return;
        }
        List<Long> readIds = readRequests.keySet().stream().sorted().toList();
        Map<Long, JobPostEntity> readCurrent = baseMapper.selectList(
                        new LambdaQueryWrapper<JobPostEntity>()
                                .in(JobPostEntity::getId, readIds)
                                .orderByAsc(JobPostEntity::getId)
                                .last("FOR UPDATE"))
                .stream().collect(Collectors.toMap(JobPostEntity::getId, readJob -> readJob));
        if (readCurrent.size() != readIds.size()) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "部分岗位不存在");
        }
        LocalDateTime readNow = LocalDateTime.now();
        List<JobPostEntity> updateJobs = readIds.stream().map(readId -> {
            JobRecycleRequest readRequest = readRequests.get(readId);
            JobPostEntity updateJob = new JobPostEntity();
            updateJob.setId(readId);
            updateJob.setStatus(JobPostStatus.RECYCLED);
            updateJob.setRecommended(false);
            updateJob.setRecycleReason(cleanReason(readRequest.getReason()));
            updateJob.setRecycledAt(readNow);
            updateJob.setUpdatedAt(nextEvidenceVersion(readCurrent.get(readId).getUpdatedAt()));
            return updateJob;
        }).toList();
        // updateBatchById opens a batch SqlSession that can wait on the locks
        // held by this transaction. Use the transaction-bound mapper so the
        // ordered FOR UPDATE locks and updates share one connection.
        for (JobPostEntity updateJob : updateJobs) {
            if (baseMapper.updateById(updateJob) != 1) {
                throw ServiceException.of(ResultCode.CONFLICT, "岗位批量回收失败，请刷新后重试");
            }
        }
    }

    @Transactional
    @Override
    public void restoreJob(Long id) {
        JobPostEntity readJob = lockJob(id);
        if (readJob.getStatus() != JobPostStatus.RECYCLED) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "回收站中不存在该岗位");
        }
        if (StringUtils.hasText(readJob.getRecycleReason())
                && readJob.getRecycleReason().startsWith(DEDUP_EVIDENCE_VERSION + ";")) {
            throw ServiceException.of(ResultCode.CONFLICT, "系统去重证据不能恢复为岗位");
        }
        boolean readUpdated = lambdaUpdate()
                .eq(JobPostEntity::getId, id)
                .eq(JobPostEntity::getStatus, JobPostStatus.RECYCLED)
                .set(JobPostEntity::getStatus, JobPostStatus.OFFLINE)
                .set(JobPostEntity::getRecycleReason, null)
                .set(JobPostEntity::getRecycledAt, null)
                .set(JobPostEntity::getUpdatedAt, nextEvidenceVersion(readJob.getUpdatedAt()))
                .update();
        if (!readUpdated) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "回收站中不存在该岗位");
        }
    }

    private JobPostEntity lockJob(Long readId) {
        JobPostEntity readJob = baseMapper.selectOne(
                new LambdaQueryWrapper<JobPostEntity>()
                        .eq(JobPostEntity::getId, readId)
                        .last("FOR UPDATE"));
        if (readJob == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "岗位不存在");
        }
        return readJob;
    }

    private boolean evidenceExists(String readMarker) {
        return baseMapper.selectCount(new LambdaQueryWrapper<JobPostEntity>()
                .eq(JobPostEntity::getStatus, JobPostStatus.RECYCLED)
                .eq(JobPostEntity::getRecycleReason, readMarker)) > 0;
    }

    private boolean evidenceRoleExists(Long readKeeperId, String readRole) {
        String readPrefix = DEDUP_EVIDENCE_VERSION + ";keeper=" + readKeeperId
                + ";role=" + readRole + ";";
        return baseMapper.selectCount(new LambdaQueryWrapper<JobPostEntity>()
                .eq(JobPostEntity::getStatus, JobPostStatus.RECYCLED)
                .likeRight(JobPostEntity::getRecycleReason, readPrefix)) > 0;
    }

    private boolean persistEvidenceSnapshot(
            JobPostEntity readSnapshot,
            String readMarker,
            boolean preserveCreatedAt) {
        if (evidenceExists(readMarker)) {
            return false;
        }
        JobPostEntity createEvidence = BeanUtil.copyProperties(
                readSnapshot, JobPostEntity.class);
        createEvidence.setId(null);
        normalizeRequirements(createEvidence);
        createEvidence.setStatus(JobPostStatus.RECYCLED);
        createEvidence.setRecycleReason(readMarker);
        createEvidence.setRecycledAt(LocalDateTime.now());
        createEvidence.setCreatedBy(null);
        if (!preserveCreatedAt) {
            createEvidence.setCreatedAt(null);
        }
        createEvidence.setUpdatedAt(null);
        if (baseMapper.insert(createEvidence) != 1) {
            throw ServiceException.of(ResultCode.CONFLICT, "岗位去重证据保存失败");
        }
        return true;
    }

    private void validateIncomingEvidence(JobPostEntity readIncoming) {
        if (readIncoming.getSourceType() == null
                || !StringUtils.hasText(readIncoming.getCompanyName())
                || !StringUtils.hasText(readIncoming.getPositionName())
                || readIncoming.getJobCategory() == null
                || readIncoming.getRecruitType() == null) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED,
                    "incoming 缺少可持久化的来源、公司、岗位、岗位分类或招聘类型");
        }
    }

    private LocalDateTime nextEvidenceVersion(LocalDateTime readCurrent) {
        LocalDateTime readNow = LocalDateTime.now().withNano(0);
        if (readCurrent == null) {
            return readNow;
        }
        LocalDateTime readNext = readCurrent.withNano(0).plusSeconds(1);
        return readNow.isAfter(readNext) ? readNow : readNext;
    }

    private String evidenceMarker(Long readKeeperId, String readRole, String readHash) {
        return DEDUP_EVIDENCE_VERSION + ";keeper=" + readKeeperId
                + ";role=" + readRole + ";sha256=" + readHash;
    }

    private String evidenceHash(JobPostEntity readJob) {
        List<Object> readFields = Arrays.asList(
                readJob.getSourceType(), readJob.getSourceUrl(), readJob.getCompanyName(),
                readJob.getDepartment(), readJob.getPositionName(), readJob.getJobCategory(),
                readJob.getJobSubCategory(), readJob.getRecruitType(), readJob.getHeadcount(),
                readJob.getHeadcountDisplay(), readJob.getWorkStartDate(), readJob.getWorkEndDate(),
                readJob.getApplicationDeadline(), readJob.getWorkDaysPerWeek(),
                readJob.getWorkTimeRequirement(), readJob.getWorkDurationType(),
                readJob.getWorkPeriodType(), readJob.getWorkMode(), readJob.getWorkCity(),
                readJob.getWorkCities(), readJob.getWorkProvince(), readJob.getWorkLocation(),
                readJob.getSalaryMin(), readJob.getSalaryMax(), readJob.getSalaryDisplay(),
                readJob.getCareerDirection(), readJob.getJobDesc(), readJob.getReqEduLevel(),
                readJob.getReqEduLevels(), readJob.getReqMajor(), readJob.getReqGradYear(),
                readJob.getReqSkills(), readJob.getReqOther(), readJob.getInternalCompensation(),
                readJob.getContactName(), readJob.getContactInfo(), readJob.getInternalRemark(),
                readJob.getRecommended(), readJob.getStatus(), readJob.getRecycleReason());
        StringBuilder createCanonical = new StringBuilder();
        for (int readIndex = 0; readIndex < readFields.size(); readIndex++) {
            String readValue = canonicalEvidenceValue(readFields.get(readIndex));
            createCanonical.append(readIndex).append(':')
                    .append(readValue.length()).append(':').append(readValue).append('|');
        }
        try {
            byte[] readDigest = MessageDigest.getInstance("SHA-256").digest(
                    createCanonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(readDigest);
        } catch (NoSuchAlgorithmException readError) {
            throw new IllegalStateException("SHA-256 is unavailable", readError);
        }
    }

    private String canonicalEvidenceValue(Object readValue) {
        if (readValue == null) {
            return "<null>";
        }
        if (readValue instanceof Iterable<?> readValues) {
            List<String> readCanonical = new ArrayList<>();
            readValues.forEach(readItem -> readCanonical.add(canonicalEvidenceValue(readItem)));
            readCanonical.sort(String::compareTo);
            StringBuilder createList = new StringBuilder("[");
            for (String readItem : readCanonical) {
                createList.append(readItem.length()).append(':').append(readItem).append('|');
            }
            return createList.append(']').toString();
        }
        String readText = readValue instanceof Enum<?> readEnum
                ? readEnum.name() : String.valueOf(readValue);
        return Normalizer.normalize(readText.trim(), Normalizer.Form.NFKC);
    }

    private JobPostEntity mergeCompatibleEvidence(
            JobPostEntity readKeeper, JobPostEntity readIncoming) {
        validateExternalJobIds(readKeeper, readIncoming);
        JobPostEntity updateMerged = BeanUtil.copyProperties(readKeeper, JobPostEntity.class);

        // This is an LLM classification rather than employer-provided identity
        // evidence. Keep the canonical value, only filling an empty keeper.
        updateMerged.setJobSubCategory(readKeeper.getJobSubCategory() == null
                ? readIncoming.getJobSubCategory() : readKeeper.getJobSubCategory());
        updateMerged.setRecruitType(mergeRecruitType(
                readKeeper.getRecruitType(), readIncoming.getRecruitType()));
        updateMerged.setDepartment(mergeCompatibleString(
                readKeeper.getDepartment(), readIncoming.getDepartment(), "部门"));
        updateMerged.setHeadcount(mergeCompatibleValue(
                readKeeper.getHeadcount(), readIncoming.getHeadcount(), "招聘人数"));
        updateMerged.setHeadcountDisplay(mergeDisplayString(
                readKeeper.getHeadcountDisplay(), readIncoming.getHeadcountDisplay(), "招聘人数说明"));
        updateMerged.setWorkStartDate(mergeCompatibleValue(
                readKeeper.getWorkStartDate(), readIncoming.getWorkStartDate(), "工作开始日期"));
        updateMerged.setWorkEndDate(mergeCompatibleValue(
                readKeeper.getWorkEndDate(), readIncoming.getWorkEndDate(), "工作结束日期"));
        updateMerged.setApplicationDeadline(mergeApplicationDeadline(
                readKeeper, readIncoming.getApplicationDeadline()));
        updateMerged.setWorkDaysPerWeek(mergeCompatibleValue(
                readKeeper.getWorkDaysPerWeek(), readIncoming.getWorkDaysPerWeek(), "每周工作天数"));
        updateMerged.setWorkDurationType(mergeCompatibleValue(
                readKeeper.getWorkDurationType(), readIncoming.getWorkDurationType(), "工作时长类型"));
        updateMerged.setWorkPeriodType(mergeCompatibleValue(
                readKeeper.getWorkPeriodType(), readIncoming.getWorkPeriodType(), "工作周期类型"));
        updateMerged.setWorkMode(mergeCompatibleValue(
                readKeeper.getWorkMode(), readIncoming.getWorkMode(), "工作形式"));
        mergeCities(updateMerged, readKeeper, readIncoming);
        updateMerged.setWorkProvince(mergeCompatibleString(
                readKeeper.getWorkProvince(), readIncoming.getWorkProvince(), "工作省份"));
        updateMerged.setWorkLocation(mergeText(
                readKeeper.getWorkLocation(), readIncoming.getWorkLocation(),
                "工作地点", 128, null));
        updateMerged.setSalaryMin(mergeCompatibleValue(
                readKeeper.getSalaryMin(), readIncoming.getSalaryMin(), "最低薪资"));
        updateMerged.setSalaryMax(mergeCompatibleValue(
                readKeeper.getSalaryMax(), readIncoming.getSalaryMax(), "最高薪资"));
        updateMerged.setSalaryDisplay(mergeDisplayString(
                readKeeper.getSalaryDisplay(), readIncoming.getSalaryDisplay(), "薪资说明"));
        mergeEducation(updateMerged, readKeeper, readIncoming);
        updateMerged.setReqMajor(mergeMajor(
                readKeeper.getReqMajor(), readIncoming.getReqMajor()));
        updateMerged.setReqGradYear(mergeGraduationYear(
                readKeeper.getReqGradYear(), readIncoming.getReqGradYear()));

        updateMerged.setWorkTimeRequirement(mergeText(
                readKeeper.getWorkTimeRequirement(), readIncoming.getWorkTimeRequirement(),
                "工作时间要求", null, MYSQL_TEXT_SAFE_BYTES));
        updateMerged.setCareerDirection(mergeText(
                readKeeper.getCareerDirection(), readIncoming.getCareerDirection(),
                "职业方向", null, MYSQL_TEXT_SAFE_BYTES));
        updateMerged.setJobDesc(mergeText(
                readKeeper.getJobDesc(), readIncoming.getJobDesc(),
                "岗位描述", null, MYSQL_TEXT_SAFE_BYTES));
        updateMerged.setReqSkills(mergeText(
                readKeeper.getReqSkills(), readIncoming.getReqSkills(),
                "技能要求", 512, null));
        updateMerged.setReqOther(mergeText(
                readKeeper.getReqOther(), readIncoming.getReqOther(),
                "其他要求", null, MYSQL_TEXT_SAFE_BYTES));
        updateMerged.setInternalCompensation(mergeText(
                readKeeper.getInternalCompensation(), readIncoming.getInternalCompensation(),
                "内部薪酬信息", null, MYSQL_TEXT_SAFE_BYTES));
        updateMerged.setContactName(mergeText(
                readKeeper.getContactName(), readIncoming.getContactName(),
                "联系人", 128, null));
        updateMerged.setContactInfo(mergeText(
                readKeeper.getContactInfo(), readIncoming.getContactInfo(),
                "联系方式", 256, null));
        updateMerged.setInternalRemark(mergeText(
                readKeeper.getInternalRemark(), readIncoming.getInternalRemark(),
                "内部备注", null, MYSQL_TEXT_SAFE_BYTES));
        updateMerged.setRecommended(Boolean.TRUE.equals(readKeeper.getRecommended())
                || Boolean.TRUE.equals(readIncoming.getRecommended()));
        return updateMerged;
    }

    private void mergeCities(
            JobPostEntity updateMerged,
            JobPostEntity readKeeper,
            JobPostEntity readIncoming) {
        List<String> readCurrent = cityValues(readKeeper);
        List<String> readNew = cityValues(readIncoming);
        Set<String> readCurrentKeys = readCurrent.stream()
                .map(this::cityKey).filter(StringUtils::hasText).collect(Collectors.toSet());
        Set<String> readNewKeys = readNew.stream()
                .map(this::cityKey).filter(StringUtils::hasText).collect(Collectors.toSet());
        if (!readCurrentKeys.isEmpty() && !readNewKeys.isEmpty()
                && readCurrentKeys.stream().noneMatch(readNewKeys::contains)) {
            throw mergeConflict("工作城市存在冲突");
        }
        LinkedHashMap<String, String> updateCities = new LinkedHashMap<>();
        for (String readCity : readCurrent) {
            String readKey = cityKey(readCity);
            if (StringUtils.hasText(readKey)) {
                updateCities.putIfAbsent(readKey, readCity);
            }
        }
        for (String readCity : readNew) {
            String readKey = cityKey(readCity);
            if (StringUtils.hasText(readKey)) {
                updateCities.putIfAbsent(readKey, readCity);
            }
        }
        List<String> readMerged = List.copyOf(updateCities.values());
        updateMerged.setWorkCities(readMerged.isEmpty() ? null : readMerged);
        updateMerged.setWorkCity(readMerged.isEmpty() ? null : readMerged.get(0));
    }

    private List<String> cityValues(JobPostEntity readJob) {
        List<String> readValues = new ArrayList<>();
        if (readJob.getWorkCities() != null) {
            readJob.getWorkCities().stream()
                    .filter(StringUtils::hasText)
                    .flatMap(readValue -> Arrays.stream(readValue.split("[、,，;/；]+")))
                    .map(String::trim).filter(StringUtils::hasText).forEach(readValues::add);
        }
        if (readValues.isEmpty() && StringUtils.hasText(readJob.getWorkCity())) {
            for (String readValue : readJob.getWorkCity().split("[、,，;/；]+")) {
                if (StringUtils.hasText(readValue)) {
                    readValues.add(readValue.trim());
                }
            }
        }
        return readValues;
    }

    private String cityKey(String readValue) {
        String readKey = normalizeMergeText(readValue).replaceAll("\\s+", "");
        if (readKey.equals("不限") || readKey.equals("不限地点")
                || readKey.equals("地点不限") || readKey.equals("待定")
                || readKey.equals("暂无") || readKey.equals("无")) {
            return "";
        }
        if (readKey.equals("全国") || readKey.startsWith("全国各地")
                || readKey.startsWith("全国范围")
                || (readKey.startsWith("全国") && readKey.contains("城市"))) {
            return "全国";
        }
        return readKey.endsWith("市") && readKey.length() > 1
                ? readKey.substring(0, readKey.length() - 1) : readKey;
    }

    private void mergeEducation(
            JobPostEntity updateMerged,
            JobPostEntity readKeeper,
            JobPostEntity readIncoming) {
        List<EduLevel> readCurrent = educationValues(readKeeper);
        List<EduLevel> readNew = educationValues(readIncoming);
        if (!readCurrent.isEmpty() && !readNew.isEmpty()
                && !new LinkedHashSet<>(readCurrent).equals(new LinkedHashSet<>(readNew))) {
            throw mergeConflict("学历要求存在冲突");
        }
        List<EduLevel> readMerged = readCurrent.isEmpty() ? readNew : readCurrent;
        updateMerged.setReqEduLevels(readMerged.isEmpty() ? null : List.copyOf(readMerged));
        updateMerged.setReqEduLevel(readMerged.isEmpty() ? null : readMerged.get(0));
    }

    private List<EduLevel> educationValues(JobPostEntity readJob) {
        if (readJob.getReqEduLevels() != null && !readJob.getReqEduLevels().isEmpty()) {
            return readJob.getReqEduLevels().stream().filter(Objects::nonNull)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
        }
        return readJob.getReqEduLevel() == null
                ? List.of() : List.of(readJob.getReqEduLevel());
    }

    private com.fusioncareer.enums.RecruitType mergeRecruitType(
            com.fusioncareer.enums.RecruitType readCurrent,
            com.fusioncareer.enums.RecruitType readIncoming) {
        if (readIncoming == null || readIncoming == com.fusioncareer.enums.RecruitType.OTHER) {
            return readCurrent;
        }
        if (readCurrent == null || readCurrent == com.fusioncareer.enums.RecruitType.OTHER) {
            return readIncoming;
        }
        if (readCurrent != readIncoming) {
            throw mergeConflict("招聘类型存在冲突");
        }
        return readCurrent;
    }

    private <T> T mergeCompatibleValue(T readCurrent, T readIncoming, String readLabel) {
        if (readIncoming == null) {
            return readCurrent;
        }
        if (readCurrent == null) {
            return readIncoming;
        }
        if (!Objects.equals(readCurrent, readIncoming)) {
            throw mergeConflict(readLabel + "存在冲突");
        }
        return readCurrent;
    }

    private String mergeCompatibleString(
            String readCurrent, String readIncoming, String readLabel) {
        if (!StringUtils.hasText(readIncoming)) {
            return readCurrent;
        }
        if (!StringUtils.hasText(readCurrent)) {
            return readIncoming.trim();
        }
        if (!normalizeMergeText(readCurrent).equals(normalizeMergeText(readIncoming))) {
            throw mergeConflict(readLabel + "存在冲突");
        }
        return readCurrent;
    }

    private String mergeDisplayString(
            String readCurrent, String readIncoming, String readLabel) {
        if (!StringUtils.hasText(readIncoming)) {
            return readCurrent;
        }
        if (!StringUtils.hasText(readCurrent)) {
            return readIncoming.trim();
        }
        if (!normalizeDisplay(readCurrent).equals(normalizeDisplay(readIncoming))) {
            throw mergeConflict(readLabel + "存在冲突");
        }
        return readCurrent;
    }

    private String normalizeDisplay(String readValue) {
        String readText = normalizeMergeText(readValue).replace(" ", "");
        if (readText.matches(".*\\d.*")) {
            return readText.replaceAll("[‐‑‒–—―~～至]", "-");
        }
        return readText.replaceAll("[^\\p{L}\\p{N}_]+", "");
    }

    private String mergeMajor(String readCurrent, String readIncoming) {
        if (!StringUtils.hasText(readIncoming)) {
            return readCurrent;
        }
        if (!StringUtils.hasText(readCurrent)) {
            return readIncoming.trim();
        }
        Set<String> readCurrentTokens = requirementTokens(readCurrent);
        Set<String> readIncomingTokens = requirementTokens(readIncoming);
        if (!readCurrentTokens.equals(readIncomingTokens)) {
            throw mergeConflict("专业要求存在冲突");
        }
        return readCurrent;
    }

    private Set<String> requirementTokens(String readValue) {
        if (normalizeMergeText(readValue).contains("不限")) {
            return Set.of("<unrestricted>");
        }
        return Arrays.stream(readValue.split("[、,，;；]+"))
                .map(this::normalizeMergeText)
                .map(readToken -> readToken.replaceAll("[^\\p{L}\\p{N}_+#./]+", ""))
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String mergeGraduationYear(String readCurrent, String readIncoming) {
        if (!StringUtils.hasText(readIncoming)) {
            return readCurrent;
        }
        if (!StringUtils.hasText(readCurrent)) {
            return readIncoming.trim();
        }
        if (normalizeMergeText(readCurrent).equals(normalizeMergeText(readIncoming))) {
            return readCurrent;
        }
        Set<String> readCurrentYears = graduationYears(readCurrent);
        Set<String> readIncomingYears = graduationYears(readIncoming);
        Set<String> readCurrentFamilies = graduationFamilies(readCurrent);
        Set<String> readIncomingFamilies = graduationFamilies(readIncoming);
        if ((readCurrentFamilies.contains("NON_FRESH")
                && readIncomingFamilies.contains("FRESH"))
                || (readIncomingFamilies.contains("NON_FRESH")
                && readCurrentFamilies.contains("FRESH"))) {
            throw mergeConflict("毕业生类型存在冲突");
        }
        if (!readCurrentYears.isEmpty() && !readIncomingYears.isEmpty()
                && !readCurrentYears.equals(readIncomingYears)) {
            throw mergeConflict("毕业年份存在冲突");
        }
        String readCurrentKey = normalizeMergeText(readCurrent);
        String readIncomingKey = normalizeMergeText(readIncoming);
        if (readCurrentKey.contains(readIncomingKey)) {
            return readCurrent;
        }
        if (readIncomingKey.contains(readCurrentKey)) {
            return readIncoming.trim();
        }
        String readMerged = readCurrent.stripTrailing() + "、" + readIncoming.stripLeading();
        validateMergedText(readMerged, "毕业年份", 16, null);
        return readMerged;
    }

    private Set<String> graduationYears(String readValue) {
        Set<String> readYears = new LinkedHashSet<>();
        GRADUATION_YEAR.matcher(readValue).results()
                .map(readMatch -> readMatch.group())
                .forEach(readYears::add);
        return readYears;
    }

    private Set<String> graduationFamilies(String readValue) {
        String readText = normalizeMergeText(readValue);
        Set<String> readFamilies = new LinkedHashSet<>();
        if (readText.contains("非应届") || readText.contains("往届")) {
            readFamilies.add("NON_FRESH");
        }
        if (FRESH_GRADUATE.matcher(readText).find()) {
            readFamilies.add("FRESH");
        }
        if (readText.contains("实习")) {
            readFamilies.add("INTERN");
        }
        return readFamilies;
    }

    private void validateExternalJobIds(JobPostEntity readKeeper, JobPostEntity readIncoming) {
        Set<String> readCurrent = externalJobIds(readKeeper);
        Set<String> readNew = externalJobIds(readIncoming);
        if (!readCurrent.isEmpty() && !readNew.isEmpty() && !readCurrent.equals(readNew)) {
            throw mergeConflict("职位 ID 存在冲突");
        }
    }

    private Set<String> externalJobIds(JobPostEntity readJob) {
        Set<String> readIds = new LinkedHashSet<>();
        for (String readText : Arrays.asList(
                readJob.getJobDesc(), readJob.getReqSkills(),
                readJob.getReqOther(), readJob.getContactInfo())) {
            if (!StringUtils.hasText(readText)) {
                continue;
            }
            EXTERNAL_JOB_ID.matcher(readText).results()
                    .map(readMatch -> readMatch.group(1).toLowerCase(Locale.ROOT))
                    .filter(this::isExternalJobId)
                    .forEach(readIds::add);
        }
        return readIds;
    }

    private boolean isExternalJobId(String readValue) {
        return !EXTERNAL_JOB_ID_STOPWORDS.contains(readValue)
                && !readValue.startsWith("详见");
    }

    private String mergeText(
            String readCurrent,
            String readIncoming,
            String readLabel,
            Integer readMaxCharacters,
            Integer readMaxBytes) {
        if (!StringUtils.hasText(readIncoming)) {
            return readCurrent;
        }
        String readNew = readIncoming.trim();
        if (!StringUtils.hasText(readCurrent)) {
            validateMergedText(readNew, readLabel, readMaxCharacters, readMaxBytes);
            return readNew;
        }
        String readCurrentKey = normalizeMergeText(readCurrent);
        String readNewKey = normalizeMergeText(readNew);
        if (readCurrentKey.contains(readNewKey)) {
            return readCurrent;
        }
        if (readNewKey.contains(readCurrentKey)) {
            validateMergedText(readNew, readLabel, readMaxCharacters, readMaxBytes);
            return readNew;
        }
        String readMerged = readCurrent.stripTrailing() + "\n" + readNew.stripLeading();
        validateMergedText(readMerged, readLabel, readMaxCharacters, readMaxBytes);
        return readMerged;
    }

    private void validateMergedText(
            String readValue,
            String readLabel,
            Integer readMaxCharacters,
            Integer readMaxBytes) {
        int readCharacters = readValue.codePointCount(0, readValue.length());
        if (readMaxCharacters != null && readCharacters > readMaxCharacters) {
            throw mergeConflict(readLabel + "合并后超过字段长度限制");
        }
        if (readMaxBytes != null
                && readValue.getBytes(StandardCharsets.UTF_8).length > readMaxBytes) {
            throw mergeConflict(readLabel + "合并后超过字段长度限制");
        }
    }

    private String normalizeMergeText(String readValue) {
        return Normalizer.normalize(String.valueOf(readValue).trim(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizePositionName(String readValue) {
        return normalizeMergeText(readValue).replace(" ", "");
    }

    private LocalDate laterDate(LocalDate readCurrent, LocalDate readIncoming) {
        if (readIncoming == null || (readCurrent != null && !readIncoming.isAfter(readCurrent))) {
            return readCurrent;
        }
        return readIncoming;
    }

    private LocalDate mergeApplicationDeadline(
            JobPostEntity readKeeper, LocalDate readIncoming) {
        LocalDate readCurrent = readKeeper.getApplicationDeadline();
        if (readIncoming == null) {
            return readCurrent;
        }
        boolean readLegacyInferred = readCurrent != null
                && readKeeper.getCreatedAt() != null
                && readCurrent.equals(readKeeper.getCreatedAt().toLocalDate().plusMonths(1));
        return readLegacyInferred ? readIncoming : laterDate(readCurrent, readIncoming);
    }

    private ServiceException mergeConflict(String readMessage) {
        return ServiceException.of(ResultCode.CONFLICT, readMessage);
    }

    private boolean mergeFieldsChanged(JobPostEntity first, JobPostEntity second) {
        return !Objects.equals(first.getJobSubCategory(), second.getJobSubCategory())
                || !Objects.equals(first.getRecruitType(), second.getRecruitType())
                || !Objects.equals(first.getDepartment(), second.getDepartment())
                || !Objects.equals(first.getHeadcount(), second.getHeadcount())
                || !Objects.equals(first.getHeadcountDisplay(), second.getHeadcountDisplay())
                || !Objects.equals(first.getWorkStartDate(), second.getWorkStartDate())
                || !Objects.equals(first.getWorkEndDate(), second.getWorkEndDate())
                || !Objects.equals(first.getApplicationDeadline(), second.getApplicationDeadline())
                || !Objects.equals(first.getWorkDaysPerWeek(), second.getWorkDaysPerWeek())
                || !Objects.equals(first.getWorkTimeRequirement(), second.getWorkTimeRequirement())
                || !Objects.equals(first.getWorkDurationType(), second.getWorkDurationType())
                || !Objects.equals(first.getWorkPeriodType(), second.getWorkPeriodType())
                || !Objects.equals(first.getWorkMode(), second.getWorkMode())
                || !Objects.equals(first.getWorkCity(), second.getWorkCity())
                || !Objects.equals(first.getWorkCities(), second.getWorkCities())
                || !Objects.equals(first.getWorkProvince(), second.getWorkProvince())
                || !Objects.equals(first.getWorkLocation(), second.getWorkLocation())
                || !Objects.equals(first.getSalaryMin(), second.getSalaryMin())
                || !Objects.equals(first.getSalaryMax(), second.getSalaryMax())
                || !Objects.equals(first.getSalaryDisplay(), second.getSalaryDisplay())
                || !Objects.equals(first.getCareerDirection(), second.getCareerDirection())
                || !Objects.equals(first.getJobDesc(), second.getJobDesc())
                || !Objects.equals(first.getReqEduLevel(), second.getReqEduLevel())
                || !Objects.equals(first.getReqEduLevels(), second.getReqEduLevels())
                || !Objects.equals(first.getReqMajor(), second.getReqMajor())
                || !Objects.equals(first.getReqGradYear(), second.getReqGradYear())
                || !Objects.equals(first.getReqSkills(), second.getReqSkills())
                || !Objects.equals(first.getReqOther(), second.getReqOther())
                || !Objects.equals(first.getInternalCompensation(), second.getInternalCompensation())
                || !Objects.equals(first.getContactName(), second.getContactName())
                || !Objects.equals(first.getContactInfo(), second.getContactInfo())
                || !Objects.equals(first.getInternalRemark(), second.getInternalRemark())
                || !Objects.equals(first.getRecommended(), second.getRecommended());
    }

    private JobPostEntity mergePatch(JobPostEntity readMerged) {
        JobPostEntity updatePatch = BeanUtil.copyProperties(readMerged, JobPostEntity.class);
        updatePatch.setId(null);
        updatePatch.setSourceType(null);
        updatePatch.setSourceUrl(null);
        updatePatch.setCompanyName(null);
        updatePatch.setPositionName(null);
        updatePatch.setJobCategory(null);
        updatePatch.setStatus(null);
        updatePatch.setRecycleReason(null);
        updatePatch.setRecycledAt(null);
        updatePatch.setCreatedBy(null);
        updatePatch.setCreatedAt(null);
        updatePatch.setUpdatedAt(null);
        return updatePatch;
    }

    private String cleanReason(String readReason) {
        String readValue = StringUtils.hasText(readReason) ? readReason.trim() : "人工删除";
        return readValue.length() <= 512 ? readValue : readValue.substring(0, 512);
    }

    private void prepareRecycleFields(JobPostEntity updateJob) {
        if (updateJob.getStatus() == JobPostStatus.RECYCLED) {
            updateJob.setRecommended(false);
            updateJob.setRecycleReason(cleanReason(updateJob.getRecycleReason()));
            updateJob.setRecycledAt(LocalDateTime.now());
        }
    }

    // ==================== 私有方法 ====================

    private LambdaQueryWrapper<JobPostEntity> buildJobQuery(JobPostQueryRequest readQuery) {
        return buildJobQuery(readQuery, false);
    }

    private LambdaQueryWrapper<JobPostEntity> buildJobQuery(JobPostQueryRequest readQuery,
                                                          boolean readFullText) {
        LambdaQueryWrapper<JobPostEntity> buildQuery = new LambdaQueryWrapper<>();
        buildQuery.eq(readQuery.getJobCategory() != null, JobPostEntity::getJobCategory, readQuery.getJobCategory())
         .eq(readQuery.getJobSubCategory() != null, JobPostEntity::getJobSubCategory, readQuery.getJobSubCategory())
         .eq(readQuery.getRecruitType() != null, JobPostEntity::getRecruitType, readQuery.getRecruitType())
         .eq(readQuery.getWorkDurationType() != null, JobPostEntity::getWorkDurationType, readQuery.getWorkDurationType())
         .eq(readQuery.getWorkPeriodType() != null, JobPostEntity::getWorkPeriodType, readQuery.getWorkPeriodType())
         .eq(readQuery.getWorkMode() != null, JobPostEntity::getWorkMode, readQuery.getWorkMode())
         .eq(StringUtils.hasText(readQuery.getWorkProvince()), JobPostEntity::getWorkProvince, readQuery.getWorkProvince())
         .and(StringUtils.hasText(readQuery.getWorkCity()), readCity -> readCity
                 .eq(JobPostEntity::getWorkCity, readQuery.getWorkCity())
                 .or().apply("JSON_CONTAINS(work_cities, JSON_QUOTE({0}))", readQuery.getWorkCity()))
         .and(readQuery.getReqEduLevel() != null, readEducation -> readEducation
                 .eq(JobPostEntity::getReqEduLevel, readQuery.getReqEduLevel())
                 .or().apply("JSON_CONTAINS(req_edu_levels, JSON_QUOTE({0}))",
                         readQuery.getReqEduLevel().name()))
         .ge(readQuery.getSalaryMin() != null, JobPostEntity::getSalaryMax, readQuery.getSalaryMin())
         .le(readQuery.getSalaryMax() != null, JobPostEntity::getSalaryMin, readQuery.getSalaryMax())
         .eq(readQuery.getRecommended() != null, JobPostEntity::getRecommended, readQuery.getRecommended())
         .eq(readQuery.getStatus() != null, JobPostEntity::getStatus, readQuery.getStatus())
         .eq(readQuery.getSourceType() != null, JobPostEntity::getSourceType, readQuery.getSourceType())
         .and(Boolean.TRUE.equals(readQuery.getInternalApply()), readSource -> readSource
                 .isNull(JobPostEntity::getSourceUrl)
                 .or().eq(JobPostEntity::getSourceUrl, ""));
        if (StringUtils.hasText(readQuery.getKeyword())) {
            if (readFullText) {
                String readPattern = literalContainsPattern(readQuery.getKeyword().trim());
                buildQuery.and(readKeyword -> {
                    for (int readIndex = 0; readIndex < ADMIN_FULL_TEXT_COLUMNS.size(); readIndex++) {
                        if (readIndex > 0) {
                            readKeyword.or();
                        }
                        readKeyword.apply(ADMIN_FULL_TEXT_COLUMNS.get(readIndex)
                                + " LIKE {0} ESCAPE '!'", readPattern);
                    }
                });
            } else {
                buildQuery.and(readKeyword -> readKeyword
                        .like(JobPostEntity::getPositionName, readQuery.getKeyword())
                        .or().like(JobPostEntity::getCompanyName, readQuery.getKeyword()));
            }
        }
        sortJobs(buildQuery, readQuery.getSortBy());
        if (readFullText && readQuery.getSortBy() != JobPostSort.DEADLINE) {
            buildQuery.orderByDesc(JobPostEntity::getId);
        }
        return buildQuery;
    }

    private String literalContainsPattern(String readKeyword) {
        // Explicit escape character keeps literal matching independent of MySQL backslash mode.
        return "%" + readKeyword.replace("!", "!!")
                .replace("\\", "!\\").replace("%", "!%").replace("_", "!_") + "%";
    }

    private void sortJobs(LambdaQueryWrapper<JobPostEntity> buildQuery, JobPostSort readSort) {
        if (readSort == JobPostSort.DEADLINE) {
            buildQuery.last("ORDER BY application_deadline IS NULL, application_deadline ASC, created_at DESC");
            return;
        }
        buildQuery.orderByDesc(JobPostEntity::getCreatedAt);
    }

    private PageResult<JobPostResponse> mapPage(Page<JobPostEntity> readEntities) {
        List<JobPostResponse> readJobs = readEntities.getRecords().stream()
                .map(this::toResponse)
                .toList();
        mapApplications(readJobs);

        PageResult<JobPostResponse> readPage = new PageResult<>(readEntities.getTotal(),
                (int) readEntities.getCurrent(), (int) readEntities.getSize());
        readPage.addAll(readJobs);
        return readPage;
    }

    private void mapApplications(List<? extends JobPostResponse> updateJobs) {
        if (updateJobs.isEmpty()) {
            return;
        }
        List<Long> readJobIds = updateJobs.stream().map(JobPostResponse::getId).toList();
        Map<Long, Long> readCounts = readAnswerMapper.countApplications(readJobIds).stream()
                .collect(Collectors.toMap(JobPostApplicationCount::getJobPostId,
                        JobPostApplicationCount::getApplicationCount));
        updateJobs.forEach(updateJob -> updateJob.setApplicationCount(
                readCounts.getOrDefault(updateJob.getId(), 0L)));
    }

    private JobPostResponse toResponse(JobPostEntity entity) {
        if (entity == null) return null;
        JobPostResponse resp = BeanUtil.copyProperties(entity, JobPostResponse.class);
        copyRequirements(entity, resp);
        resp.setApplicationCount(0L);
        return resp;
    }

    private JobPostAdminResponse toAdminResponse(JobPostEntity entity) {
        if (entity == null) return null;
        JobPostAdminResponse resp = BeanUtil.copyProperties(entity, JobPostAdminResponse.class);
        copyRequirements(entity, resp);
        resp.setApplicationCount(0L);
        return resp;
    }

    private void normalizeRequirements(JobPostEntity updateJob) {
        List<String> readCities = updateJob.getWorkCities() == null ? List.of()
                : updateJob.getWorkCities().stream()
                .filter(StringUtils::hasText).map(String::trim)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (readCities.isEmpty() && StringUtils.hasText(updateJob.getWorkCity())) {
            readCities = List.of(updateJob.getWorkCity().trim());
        }
        updateJob.setWorkCities(readCities.isEmpty() ? null : readCities);
        updateJob.setWorkCity(readCities.isEmpty() ? null : readCities.get(0));

        List<EduLevel> readEducation = updateJob.getReqEduLevels() == null
                ? List.of() : updateJob.getReqEduLevels().stream().filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (readEducation.isEmpty() && updateJob.getReqEduLevel() != null) {
            readEducation = List.of(updateJob.getReqEduLevel());
        }
        updateJob.setReqEduLevels(readEducation.isEmpty() ? null : readEducation);
        updateJob.setReqEduLevel(readEducation.isEmpty() ? null : readEducation.get(0));
    }

    private void copyRequirements(JobPostEntity readJob, JobPostResponse updateResponse) {
        updateResponse.setWorkCities(readJob.getWorkCities() == null || readJob.getWorkCities().isEmpty()
                ? (StringUtils.hasText(readJob.getWorkCity()) ? List.of(readJob.getWorkCity()) : List.of())
                : readJob.getWorkCities());
        updateResponse.setReqEduLevels(readJob.getReqEduLevels() == null || readJob.getReqEduLevels().isEmpty()
                ? (readJob.getReqEduLevel() == null ? List.of() : List.of(readJob.getReqEduLevel()))
                : readJob.getReqEduLevels());
    }
}
