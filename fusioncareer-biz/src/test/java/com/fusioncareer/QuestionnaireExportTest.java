package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.JobCategory;
import com.fusioncareer.enums.JobPostStatus;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.enums.RecruitType;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.JobPostService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.QuestionnaireExportService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.UserProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuestionnaireExportTest {

    @Autowired
    private QuestionnaireExportService readExportService;

    @Autowired
    private UserService readUserService;

    @Autowired
    private UserProfileService readProfileService;

    @Autowired
    private JobPostService readJobService;

    @Autowired
    private JobPostQuestionService readQuestionService;

    @Autowired
    private QuestionnaireAnswerService readAnswerService;

    @Autowired
    private ResumeFileService readFileService;

    @Autowired
    private ResumeService readResumeService;

    @Autowired
    private UploadProperties readUploadProperties;

    @Autowired
    private ObjectMapper readObjectMapper;

    private Long readJobId;
    private Long readAnswerId;

    @BeforeEach
    void createExport() throws Exception {
        UserEntity createUser = new UserEntity();
        createUser.setUsername("export-user");
        createUser.setStudentId("export-student");
        createUser.setRole(UserRole.NORMAL);
        createUser.setStatus(UserStatus.NORMAL);
        readUserService.save(createUser);

        UserProfileEntity createProfile = new UserProfileEntity();
        createProfile.setUserId(createUser.getId());
        createProfile.setRealName("修改后的姓名");
        createProfile.setPhone("13800000000");
        createProfile.setMajor("新闻传播学");
        readProfileService.save(createProfile);

        ResumeEntity createResume = new ResumeEntity();
        createResume.setUserId(createUser.getId());
        createResume.setPersonalIntro("测试简历正文");
        createResume.setSkills("采访、写作");
        readResumeService.save(createResume);

        JobPostEntity createJob = new JobPostEntity();
        createJob.setCompanyName("export-company");
        createJob.setPositionName("export-job");
        createJob.setJobCategory(JobCategory.MEDIA);
        createJob.setRecruitType(RecruitType.DAILY_INTERNSHIP);
        createJob.setStatus(JobPostStatus.PUBLISHED);
        readJobService.save(createJob);
        readJobId = createJob.getId();

        JobPostQuestionRequest createQuestion = new JobPostQuestionRequest();
        createQuestion.setSortOrder(1);
        createQuestion.setTitle("简历");
        createQuestion.setQuestionType(QuestionType.FILE_UPLOAD);
        createQuestion.setRequired(true);
        JobPostQuestionRequest createTextQuestion = new JobPostQuestionRequest();
        createTextQuestion.setSortOrder(2);
        createTextQuestion.setTitle("自我介绍");
        createTextQuestion.setQuestionType(QuestionType.TEXT);
        createTextQuestion.setRequired(true);
        List<JobPostQuestionResponse> readQuestions = readQuestionService
                .saveQuestions(readJobId, List.of(createQuestion, createTextQuestion));
        JobPostQuestionResponse readQuestion = readQuestions.get(0);

        ResumeFileEntity createFile = new ResumeFileEntity();
        createFile.setUserId(createUser.getId());
        createFile.setOriginalName("../unsafe résumé.pdf");
        createFile.setStoragePath("resumes/export/resume.pdf");
        createFile.setFileSize(8L);
        createFile.setMimeType("application/pdf");
        createFile.setCreatedAt(LocalDateTime.now());
        readFileService.save(createFile);
        Path createPath = Path.of(readUploadProperties.getBaseDir(), createFile.getStoragePath());
        Files.createDirectories(createPath.getParent());
        Files.writeString(createPath, "pdf-data", StandardCharsets.UTF_8);

        QuestionnaireAnswerEntity createAnswer = new QuestionnaireAnswerEntity();
        createAnswer.setJobPostId(readJobId);
        createAnswer.setUserId(createUser.getId());
        createAnswer.setSubmissionStatus(QuestionnaireSubmissionStatus.SUBMITTED);
        createAnswer.setAnswers(readObjectMapper.writeValueAsString(List.of(
                Map.of("questionId", readQuestion.getId(), "value", String.valueOf(createFile.getId())),
                Map.of("questionId", readQuestions.get(1).getId(), "value", "测试回答"))));
        createAnswer.setCreatedAt(LocalDateTime.now());
        createAnswer.setUpdatedAt(LocalDateTime.now());
        readAnswerService.save(createAnswer);
        readAnswerId = createAnswer.getId();

        QuestionnaireAnswerEntity createDraft = new QuestionnaireAnswerEntity();
        createDraft.setJobPostId(readJobId);
        createDraft.setUserId(createUser.getId() + 1);
        createDraft.setSubmissionStatus(QuestionnaireSubmissionStatus.DRAFT);
        createDraft.setAnswers("[{\"questionId\":0,\"value\":\"draft-secret\"}]");
        readAnswerService.save(createDraft);
    }

    @AfterEach
    void deleteFiles() throws Exception {
        Path deleteRoot = Path.of(readUploadProperties.getBaseDir());
        if (Files.exists(deleteRoot)) {
            try (var readPaths = Files.walk(deleteRoot)) {
                for (Path deletePath : readPaths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(deletePath);
                }
            }
        }
    }

    @Test
    void exportCsv() {
        byte[] readCsv = readExportService.buildCsv(readJobId, List.of(readAnswerId));
        String readText = new String(readCsv, StandardCharsets.UTF_8);

        assertThat(readCsv).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(readText).contains("提交序号", "修改后的姓名", "export-student", "SUBMITTED",
                "简历", "自我介绍", "测试回答");
        assertThat(readText).doesNotContain("answers", "export-user");
        assertThat(readText).doesNotContain("draft-secret");
    }

    @Test
    void listAnswers() {
        var readPage = readAnswerService.listByJobPostId(readJobId, 1, 20);

        assertThat(readPage.getTotal()).isEqualTo(1);
        assertThat(readPage.getList()).extracting("id").containsExactly(readAnswerId);
    }

    @Test
    void exportZip() throws Exception {
        byte[] readZip = readExportService.buildZip(readJobId, null);
        List<String> readEntries = new ArrayList<>();
        String readFile = null;
        try (ZipInputStream readStream = new ZipInputStream(
                new ByteArrayInputStream(readZip), StandardCharsets.UTF_8)) {
            ZipEntry readEntry;
            while ((readEntry = readStream.getNextEntry()) != null) {
                readEntries.add(readEntry.getName());
                if (readEntry.getName().startsWith("resumes/")) {
                    readFile = new String(readStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }

        assertThat(readEntries).contains("applications.csv");
        assertThat(readEntries).contains("resumes/1_修改后的姓名_export-student_个人简历.pdf");
        assertThat(readFile).isEqualTo("pdf-data");
    }

    @Test
    void exportSelectedProfileAndResumeContent() {
        byte[] readCsv = readExportService.buildCsv(
                readJobId, List.of(readAnswerId), List.of("profile", "resume"));
        String readText = new String(readCsv, StandardCharsets.UTF_8);

        assertThat(readText).contains(
                "用户资料-手机", "13800000000", "用户资料-专业", "新闻传播学",
                "简历正文-个人简况", "测试简历正文", "简历正文-技能", "采访、写作");
    }

    @Test
    void exportSelectedResumeFiles() throws Exception {
        Long readUserId = readAnswerService.getById(readAnswerId).getUserId();
        ResumeFileEntity createFile = new ResumeFileEntity();
        createFile.setUserId(readUserId);
        createFile.setOriginalName("portfolio.docx");
        createFile.setStoragePath("resumes/export/portfolio.docx");
        createFile.setFileSize(9L);
        createFile.setMimeType("application/docx");
        createFile.setCreatedAt(LocalDateTime.now().plusSeconds(1));
        readFileService.save(createFile);
        Path createPath = Path.of(readUploadProperties.getBaseDir(), createFile.getStoragePath());
        Files.createDirectories(createPath.getParent());
        Files.writeString(createPath, "docx-data", StandardCharsets.UTF_8);

        byte[] readZip = readExportService.buildZip(
                readJobId, List.of(readAnswerId), List.of("files"));
        List<String> readEntries = new ArrayList<>();
        try (ZipInputStream readStream = new ZipInputStream(
                new ByteArrayInputStream(readZip), StandardCharsets.UTF_8)) {
            ZipEntry readEntry;
            while ((readEntry = readStream.getNextEntry()) != null) {
                readEntries.add(readEntry.getName());
            }
        }

        assertThat(readEntries).contains("applications.csv");
        assertThat(readEntries).anyMatch(readName -> readName.endsWith("_portfolio.docx"));
        assertThat(readEntries.stream().filter(readName -> readName.startsWith("resumes/"))).hasSize(2);
    }

    @Test
    void rejectUnsupportedContentSelections() {
        assertThatThrownBy(() -> readExportService.buildCsv(
                readJobId, List.of(readAnswerId), List.of("files")))
                .hasMessageContaining("format 必须为 zip");
        assertThatThrownBy(() -> readExportService.buildZip(
                readJobId, List.of(readAnswerId), List.of("unknown")))
                .hasMessageContaining("仅支持 profile、resume 或 files");
    }
}
