package com.fusioncareer;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.JobPostQueryRequest;
import com.fusioncareer.dto.res.JobPostResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostSort;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.service.JobPostService;
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

    @BeforeEach
    void createJobs() {
        createJob("alpha", "上海", "上海", 100, 200,
                LocalDate.of(2026, 9, 20), LocalDateTime.of(2026, 8, 1, 8, 0));
        createJob("beta", "上海", "上海", 250, 400,
                LocalDate.of(2026, 9, 10), LocalDateTime.of(2026, 8, 3, 8, 0));
        createJob("gamma", "广东", "广州", 500, 600,
                null, LocalDateTime.of(2026, 8, 2, 8, 0));
        createJob("delta", "广东", "深圳", 150, 300,
                LocalDate.of(2026, 9, 30), LocalDateTime.of(2026, 8, 4, 8, 0));
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
        createJob.setWorkEndDate(createDeadline);
        createJob.setCreatedAt(createTime);
        readJobService.save(createJob);
    }
}
