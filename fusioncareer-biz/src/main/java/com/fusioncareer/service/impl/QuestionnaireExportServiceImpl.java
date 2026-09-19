package com.fusioncareer.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fusioncareer.service.QuestionnaireExportService;
import com.fusioncareer.service.QuestionnaireAnswerService;
import com.fusioncareer.service.JobPostQuestionService;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserService;
import com.fusioncareer.service.UserProfileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.entity.QuestionnaireAnswerEntity;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.QuestionType;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.dto.res.JobPostQuestionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class QuestionnaireExportServiceImpl implements QuestionnaireExportService {

    private static final byte[] CSV_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Set<String> ALLOWED_CONTENT = Set.of("profile", "resume", "files");
    private static final String[][] PROFILE_COLUMNS = {
            {"realName", "用户资料-姓名"}, {"gender", "用户资料-性别"},
            {"birthDate", "用户资料-出生日期"}, {"politicalStatus", "用户资料-政治面貌"},
            {"phone", "用户资料-手机"}, {"email", "用户资料-邮箱"},
            {"wechat", "用户资料-微信"}, {"hometown", "用户资料-生源地"},
            {"grade", "用户资料-年级"}, {"major", "用户资料-专业"},
            {"eduLevel", "用户资料-学历"}, {"supervisor", "用户资料-导师"},
            {"intentionOrder", "用户资料-去向意向排序"}, {"intentionCity", "用户资料-意向城市"},
            {"intentionDream", "用户资料-理想岗位"}, {"mindset", "用户资料-就业心态"}
    };
    private static final String[][] RESUME_COLUMNS = {
            {"personalIntro", "简历正文-个人简况"}, {"basicInfo", "简历正文-基础信息"},
            {"education", "简历正文-教育背景"}, {"internship", "简历正文-实习经历"},
            {"campus", "简历正文-在校经历"}, {"awards", "简历正文-荣誉奖励"},
            {"skills", "简历正文-技能"}, {"portfolio", "简历正文-作品集"},
            {"remark", "简历正文-备注"}
    };

    private final QuestionnaireAnswerService questionnaireAnswerService;
    private final JobPostQuestionService jobPostQuestionService;
    private final ResumeFileService resumeFileService;
    private final ResumeService resumeService;
    private final UserService userService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    @Override
    public byte[] buildCsv(Long readJobId, List<Long> readAnswerIds, List<String> readContent) {
        Set<String> readSelection = normalizeContent(readContent);
        if (readSelection.contains("files")) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "导出简历文件时 format 必须为 zip");
        }
        return renderCsv(readJobId, readAnswers(readJobId, readAnswerIds), readSelection);
    }

    @Override
    public byte[] buildZip(Long readJobId, List<Long> readAnswerIds, List<String> readContent) {
        Set<String> readSelection = normalizeContent(readContent);
        List<QuestionnaireAnswerEntity> readAnswers = readAnswers(readJobId, readAnswerIds);

        try (ByteArrayOutputStream createBytes = new ByteArrayOutputStream();
             ZipOutputStream createZip = new ZipOutputStream(createBytes, StandardCharsets.UTF_8)) {
            createZip.putNextEntry(new ZipEntry("applications.csv"));
            createZip.write(renderCsv(readJobId, readAnswers, readSelection));
            createZip.closeEntry();

            if (readSelection.contains("files")) {
                writeUserResumeFiles(createZip, readAnswers);
            } else if (readSelection.isEmpty()) {
                writeLegacyAnswerFiles(createZip, readJobId, readAnswers);
            }
            createZip.finish();
            return createBytes.toByteArray();
        } catch (IOException readError) {
            throw new IllegalStateException("导出 ZIP 失败", readError);
        }
    }

    @Override
    public String sanitizeFilename(String readFilename) {
        String updateName = readFilename == null ? "file" : readFilename.replace('\\', '/');
        updateName = updateName.substring(updateName.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._\\-\\p{IsHan}]", "_");
        if (updateName.isBlank() || updateName.equals(".") || updateName.equals("..")) {
            return "file";
        }
        return updateName;
    }

    private String fileExtension(String readFilename) {
        String readName = sanitizeFilename(readFilename);
        int readDot = readName.lastIndexOf('.');
        return readDot > 0 ? readName.substring(readDot) : "";
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

    private byte[] renderCsv(Long readJobId, List<QuestionnaireAnswerEntity> readAnswers,
                             Set<String> readContent) {
        List<JobPostQuestionResponse> readQuestions = jobPostQuestionService.listByJobPostId(readJobId);
        Map<Long, UserEntity> readUsers = readUsers(readAnswers);
        Map<Long, UserProfileEntity> readProfiles = readProfiles(readAnswers);
        Map<Long, ResumeEntity> readResumes = readContent.contains("resume")
                ? readResumes(readAnswers) : Map.of();
        StringBuilder createCsv = new StringBuilder();
        createCsv.append("提交序号,username,studentId,status,createdAt,updatedAt,reviewPassed,reviewComments");
        if (readContent.contains("profile")) appendHeaders(createCsv, PROFILE_COLUMNS);
        if (readContent.contains("resume")) appendHeaders(createCsv, RESUME_COLUMNS);
        readQuestions.forEach(readQuestion -> createCsv.append(',').append(escapeCsv(readQuestion.getTitle())));
        createCsv.append("\r\n");
        for (int readIndex = 0; readIndex < readAnswers.size(); readIndex++) {
            QuestionnaireAnswerEntity readAnswer = readAnswers.get(readIndex);
            UserEntity readUser = readUsers.get(readAnswer.getUserId());
            Map<Long, Object> readValues = answerValues(readAnswer.getAnswers());
            createCsv.append(escapeCsv(readIndex + 1)).append(',')
                    .append(escapeCsv(displayName(readUser, readProfiles.get(readAnswer.getUserId())))).append(',')
                    .append(escapeCsv(readUser == null ? null : readUser.getStudentId())).append(',')
                    .append(escapeCsv(readAnswer.getSubmissionStatus())).append(',')
                    .append(escapeCsv(readAnswer.getCreatedAt())).append(',')
                    .append(escapeCsv(readAnswer.getUpdatedAt())).append(',')
                    .append(escapeCsv(readAnswer.getReviewPassed())).append(',')
                    .append(escapeCsv(readAnswer.getReviewComments()));
            if (readContent.contains("profile")) {
                appendValues(createCsv, readProfiles.get(readAnswer.getUserId()), PROFILE_COLUMNS);
            }
            if (readContent.contains("resume")) {
                appendValues(createCsv, readResumes.get(readAnswer.getUserId()), RESUME_COLUMNS);
            }
            readQuestions.forEach(readQuestion -> createCsv.append(',')
                    .append(escapeCsv(readValues.get(readQuestion.getId()))));
            createCsv.append("\r\n");
        }
        byte[] readCsv = createCsv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] createCsvBytes = new byte[CSV_BOM.length + readCsv.length];
        System.arraycopy(CSV_BOM, 0, createCsvBytes, 0, CSV_BOM.length);
        System.arraycopy(readCsv, 0, createCsvBytes, CSV_BOM.length, readCsv.length);
        return createCsvBytes;
    }

    private Set<String> normalizeContent(List<String> readContent) {
        if (readContent == null || readContent.isEmpty()) return Set.of();
        Set<String> readSelection = new LinkedHashSet<>();
        for (String readValue : readContent) {
            String readName = readValue == null ? "" : readValue.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_CONTENT.contains(readName)) {
                throw ServiceException.of(ResultCode.VALIDATE_FAILED,
                        "导出内容仅支持 profile、resume 或 files");
            }
            readSelection.add(readName);
        }
        return readSelection;
    }

    private void appendHeaders(StringBuilder createCsv, String[][] readColumns) {
        for (String[] readColumn : readColumns) {
            createCsv.append(',').append(escapeCsv(readColumn[1]));
        }
    }

    private void appendValues(StringBuilder createCsv, Object readBean, String[][] readColumns) {
        for (String[] readColumn : readColumns) {
            Object readValue = readBean == null ? null : BeanUtil.getProperty(readBean, readColumn[0]);
            if (readValue instanceof Enum<?>) {
                Object readDescription = BeanUtil.getProperty(readValue, "desc");
                if (readDescription != null) readValue = readDescription;
            }
            createCsv.append(',').append(escapeCsv(readValue));
        }
    }

    private void writeLegacyAnswerFiles(ZipOutputStream createZip, Long readJobId,
                                        List<QuestionnaireAnswerEntity> readAnswers) throws IOException {
        Map<Long, ResumeExport> readFileOwners = readFileOwners(readJobId, readAnswers);
        for (ResumeFileEntity readFile : resumeFileService.listByIds(readFileOwners.keySet())) {
            ResumeExport readExport = readFileOwners.get(readFile.getId());
            if (readExport == null || !readExport.userId().equals(readFile.getUserId())) continue;
            String createName = "resumes/" + readExport.sequence() + "_"
                    + sanitizeFilename(readExport.username()) + "_"
                    + sanitizeFilename(readExport.studentId()) + "_个人简历"
                    + fileExtension(readFile.getOriginalName());
            writeFile(createZip, createName, readFile);
        }
    }

    private void writeUserResumeFiles(ZipOutputStream createZip,
                                      List<QuestionnaireAnswerEntity> readAnswers) throws IOException {
        if (readAnswers.isEmpty()) return;
        Map<Long, UserEntity> readUsers = readUsers(readAnswers);
        Map<Long, UserProfileEntity> readProfiles = readProfiles(readAnswers);
        Map<Long, Integer> readSequences = new LinkedHashMap<>();
        for (int readIndex = 0; readIndex < readAnswers.size(); readIndex++) {
            readSequences.putIfAbsent(readAnswers.get(readIndex).getUserId(), readIndex + 1);
        }
        List<ResumeFileEntity> readFiles = resumeFileService.lambdaQuery()
                .in(ResumeFileEntity::getUserId, readSequences.keySet())
                .orderByAsc(ResumeFileEntity::getCreatedAt)
                .list();
        for (ResumeFileEntity readFile : readFiles) {
            UserEntity readUser = readUsers.get(readFile.getUserId());
            String createName = "resumes/" + readSequences.get(readFile.getUserId()) + "_"
                    + sanitizeFilename(displayName(readUser, readProfiles.get(readFile.getUserId()))) + "_"
                    + sanitizeFilename(readUser == null ? "" : readUser.getStudentId()) + "_"
                    + readFile.getId() + "_" + sanitizeFilename(readFile.getOriginalName());
            writeFile(createZip, createName, readFile);
        }
    }

    private void writeFile(ZipOutputStream createZip, String createName,
                           ResumeFileEntity readFile) throws IOException {
        createZip.putNextEntry(new ZipEntry(createName));
        Resource readResource = resumeFileService.loadAsResource(readFile.getStoragePath());
        try (InputStream readStream = readResource.getInputStream()) {
            readStream.transferTo(createZip);
        }
        createZip.closeEntry();
    }

    private Map<Long, ResumeExport> readFileOwners(
            Long readJobId, List<QuestionnaireAnswerEntity> readAnswers) {
        Set<Long> readQuestionIds = jobPostQuestionService.listByJobPostId(readJobId).stream()
                .filter(readQuestion -> readQuestion.getQuestionType() == QuestionType.FILE_UPLOAD)
                .map(readQuestion -> readQuestion.getId())
                .collect(Collectors.toSet());
        Map<Long, UserEntity> readUsers = readUsers(readAnswers);
        Map<Long, UserProfileEntity> readProfiles = readProfiles(readAnswers);
        Map<Long, ResumeExport> readOwners = new LinkedHashMap<>();
        for (int readIndex = 0; readIndex < readAnswers.size(); readIndex++) {
            QuestionnaireAnswerEntity readAnswer = readAnswers.get(readIndex);
            UserEntity readUser = readUsers.get(readAnswer.getUserId());
            for (Map<String, Object> readItem : parseAnswers(readAnswer.getAnswers())) {
                Long readQuestionId = parseLong(readItem.get("questionId"));
                Long readFileId = parseLong(readItem.get("value"));
                if (readQuestionIds.contains(readQuestionId) && readFileId != null) {
                    readOwners.put(readFileId, new ResumeExport(
                            readAnswer.getUserId(), readIndex + 1,
                            displayName(readUser, readProfiles.get(readAnswer.getUserId())),
                            readUser == null ? "" : readUser.getStudentId()));
                }
            }
        }
        return readOwners;
    }

    private Map<Long, UserEntity> readUsers(List<QuestionnaireAnswerEntity> readAnswers) {
        return userService.listByIds(readAnswers.stream()
                        .map(QuestionnaireAnswerEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    private Map<Long, UserProfileEntity> readProfiles(List<QuestionnaireAnswerEntity> readAnswers) {
        return userProfileService.listByIds(readAnswers.stream()
                        .map(QuestionnaireAnswerEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserProfileEntity::getUserId, Function.identity()));
    }

    private Map<Long, ResumeEntity> readResumes(List<QuestionnaireAnswerEntity> readAnswers) {
        return resumeService.listByIds(readAnswers.stream()
                        .map(QuestionnaireAnswerEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(ResumeEntity::getUserId, Function.identity()));
    }

    private String displayName(UserEntity readUser, UserProfileEntity readProfile) {
        if (readProfile != null && StringUtils.hasText(readProfile.getRealName())) {
            return readProfile.getRealName();
        }
        return readUser == null ? "" : readUser.getUsername();
    }

    private Map<Long, Object> answerValues(String readAnswers) {
        Map<Long, Object> readValues = new LinkedHashMap<>();
        parseAnswers(readAnswers).forEach(readItem -> {
            Long readQuestionId = parseLong(readItem.get("questionId"));
            if (readQuestionId != null) {
                readValues.put(readQuestionId, readItem.get("value"));
            }
        });
        return readValues;
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

    private record ResumeExport(Long userId, int sequence, String username, String studentId) { }
}
