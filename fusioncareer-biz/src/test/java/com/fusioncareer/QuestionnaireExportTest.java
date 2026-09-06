package com.fusioncareer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.req.JobPostQuestionRequest;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import com.fusioncareer.entity.JobPostEntity;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.entity.UserEntity;
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
import com.fusioncareer.service.UserService;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuestionnaireExportTest {

    @Autowired
    private QuestionnaireExportService readExportService;

    @Autowired
    private UserService readUserService;

    @Autowired
    private JobPostService readJobService;

    @Autowired
    private JobPostQuestionService readQuestionService;

    @Autowired
    private QuestionnaireAnswerService readAnswerService;

    @Autowired
    private ResumeFileService readFileService;

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
        JobPostQuestionResponse readQuestion = readQuestionService
                .saveQuestions(readJobId, List.of(createQuestion)).get(0);

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
                Map.of("questionId", readQuestion.getId(), "value", String.valueOf(createFile.getId())))));
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
        assertThat(readText).contains("export-user", "export-student", "SUBMITTED");
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
        assertThat(readEntries).anyMatch(readName -> readName.startsWith("resumes/")
                && readName.endsWith("unsafe_r_sum_.pdf")
                && !readName.contains(".."));
        assertThat(readFile).isEqualTo("pdf-data");
    }
}
