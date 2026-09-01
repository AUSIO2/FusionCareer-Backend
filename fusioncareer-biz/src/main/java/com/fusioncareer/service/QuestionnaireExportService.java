package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class QuestionnaireExportService {

    private static final byte[] CSV_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final QuestionnaireAnswerService questionnaireAnswerService;
    private final JobPostQuestionService jobPostQuestionService;
    private final ResumeFileService resumeFileService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public byte[] buildCsv(Long readJobId, List<Long> readAnswerIds) {
        return buildCsv(readAnswers(readJobId, readAnswerIds));
    }

    public byte[] buildZip(Long readJobId, List<Long> readAnswerIds) {
        List<QuestionnaireAnswerEntity> readAnswers = readAnswers(readJobId, readAnswerIds);
        Map<Long, Long> readFileOwners = readFileOwners(readJobId, readAnswers);

        try (ByteArrayOutputStream createBytes = new ByteArrayOutputStream();
             ZipOutputStream createZip = new ZipOutputStream(createBytes, StandardCharsets.UTF_8)) {
            createZip.putNextEntry(new ZipEntry("applications.csv"));
            createZip.write(buildCsv(readAnswers));
            createZip.closeEntry();

            if (!readFileOwners.isEmpty()) {
                for (ResumeFileEntity readFile : resumeFileService.listByIds(readFileOwners.keySet())) {
                    Long readOwner = readFileOwners.get(readFile.getId());
                    if (readOwner == null || !readOwner.equals(readFile.getUserId())) {
                        continue;
                    }
                    String createName = "resumes/" + readOwner + "-" + readFile.getId() + "-"
                            + sanitizeFilename(readFile.getOriginalName());
                    createZip.putNextEntry(new ZipEntry(createName));
                    Resource readResource = resumeFileService.loadAsResource(readFile.getStoragePath());
                    try (InputStream readStream = readResource.getInputStream()) {
                        readStream.transferTo(createZip);
                    }
                    createZip.closeEntry();
                }
            }
            createZip.finish();
            return createBytes.toByteArray();
        } catch (IOException readError) {
            throw new IllegalStateException("导出 ZIP 失败", readError);
        }
    }

    public String sanitizeFilename(String readFilename) {
        String updateName = readFilename == null ? "file" : readFilename.replace('\\', '/');
        updateName = updateName.substring(updateName.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._\\-\\p{IsHan}]", "_");
        if (updateName.isBlank() || updateName.equals(".") || updateName.equals("..")) {
            return "file";
        }
        return updateName;
    }

    private List<QuestionnaireAnswerEntity> readAnswers(Long readJobId, List<Long> readAnswerIds) {
        LambdaQueryWrapper<QuestionnaireAnswerEntity> buildQuery =
                new LambdaQueryWrapper<QuestionnaireAnswerEntity>()
                        .eq(QuestionnaireAnswerEntity::getJobPostId, readJobId)
                        .ne(QuestionnaireAnswerEntity::getSubmissionStatus, QuestionnaireSubmissionStatus.DRAFT)
                        .orderByAsc(QuestionnaireAnswerEntity::getCreatedAt);
        if (readAnswerIds != null && !readAnswerIds.isEmpty()) {
            buildQuery.in(QuestionnaireAnswerEntity::getId, readAnswerIds);
        }
        return questionnaireAnswerService.list(buildQuery);
    }

    private byte[] buildCsv(List<QuestionnaireAnswerEntity> readAnswers) {
        Map<Long, UserEntity> readUsers = userService.listByIds(readAnswers.stream()
                        .map(QuestionnaireAnswerEntity::getUserId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        StringBuilder createCsv = new StringBuilder();
        createCsv.append("id,jobPostId,userId,username,studentId,status,answers,createdAt,updatedAt,reviewPassed,reviewComments\r\n");
        for (QuestionnaireAnswerEntity readAnswer : readAnswers) {
            UserEntity readUser = readUsers.get(readAnswer.getUserId());
            createCsv.append(escapeCsv(readAnswer.getId())).append(',')
                    .append(escapeCsv(readAnswer.getJobPostId())).append(',')
                    .append(escapeCsv(readAnswer.getUserId())).append(',')
                    .append(escapeCsv(readUser == null ? null : readUser.getUsername())).append(',')
                    .append(escapeCsv(readUser == null ? null : readUser.getStudentId())).append(',')
                    .append(escapeCsv(readAnswer.getSubmissionStatus())).append(',')
                    .append(escapeCsv(readAnswer.getAnswers())).append(',')
                    .append(escapeCsv(readAnswer.getCreatedAt())).append(',')
                    .append(escapeCsv(readAnswer.getUpdatedAt())).append(',')
                    .append(escapeCsv(readAnswer.getReviewPassed())).append(',')
                    .append(escapeCsv(readAnswer.getReviewComments())).append("\r\n");
        }
        byte[] readCsv = createCsv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] createCsvBytes = new byte[CSV_BOM.length + readCsv.length];
        System.arraycopy(CSV_BOM, 0, createCsvBytes, 0, CSV_BOM.length);
        System.arraycopy(readCsv, 0, createCsvBytes, CSV_BOM.length, readCsv.length);
        return createCsvBytes;
    }

    private Map<Long, Long> readFileOwners(Long readJobId, List<QuestionnaireAnswerEntity> readAnswers) {
        Set<Long> readQuestionIds = jobPostQuestionService.listByJobPostId(readJobId).stream()
                .filter(readQuestion -> readQuestion.getQuestionType() == QuestionType.FILE_UPLOAD)
                .map(readQuestion -> readQuestion.getId())
                .collect(Collectors.toSet());
        Map<Long, Long> readOwners = new LinkedHashMap<>();
        for (QuestionnaireAnswerEntity readAnswer : readAnswers) {
            for (Map<String, Object> readItem : parseAnswers(readAnswer.getAnswers())) {
                Long readQuestionId = parseLong(readItem.get("questionId"));
                Long readFileId = parseLong(readItem.get("value"));
                if (readQuestionIds.contains(readQuestionId) && readFileId != null) {
                    readOwners.put(readFileId, readAnswer.getUserId());
                }
            }
        }
        return readOwners;
    }

    private List<Map<String, Object>> parseAnswers(String readAnswers) {
        try {
            return objectMapper.readValue(readAnswers,
                    new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception readError) {
            return List.of();
        }
    }

    private Long parseLong(Object readValue) {
        if (readValue == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(readValue));
        } catch (NumberFormatException readError) {
            return null;
        }
    }

    private String escapeCsv(Object readValue) {
        String updateValue = readValue == null ? "" : String.valueOf(readValue);
        return '"' + updateValue.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"") + '"';
    }
}
