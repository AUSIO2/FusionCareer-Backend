package com.fusioncareer.service;

import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.dto.QuestionnaireApplicationCount;
import com.fusioncareer.dto.req.UserMemoryUpdateRequest;
import com.fusioncareer.dto.res.MyQuestionnaireListPageResponse;
import com.fusioncareer.dto.res.PersonalSpaceDocumentsResponse;
import com.fusioncareer.dto.res.PersonalSpaceResponse;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.dto.res.ResumeResponse;
import com.fusioncareer.dto.res.UserProfileResponse;
import com.fusioncareer.dto.res.UserMemoryResponse;
import com.fusioncareer.dto.res.UserChangeActionPageResponse;
import com.fusioncareer.dto.res.UserChangeActionResponse;
import com.fusioncareer.dto.res.UserChangeConfirmationResponse;
import com.fusioncareer.dto.req.UserChangeRevertResolveRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.dto.res.AiSessionResponse;
import com.fusioncareer.enums.QuestionnaireSubmissionStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 当前用户个人空间的只读聚合入口。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalSpaceService {

    private static final String PROFILE_HREF = "/api/personal-space/profile";
    private static final String RESUME_HREF = "/api/personal-space/resume";
    private static final String DOCUMENTS_HREF = "/api/personal-space/documents";
    private static final String APPLICATIONS_HREF = "/api/personal-space/applications";
    private static final String MEMORY_HREF = "/api/personal-space/memory";
    private static final String ASSISTANT_HREF = "/api/personal-space/assistant/session";

    private final UserService readUserService;
    private final UserProfileService readProfileService;
    private final ResumeService readResumeService;
    private final ResumeFileService readFileService;
    private final QuestionnaireAnswerService readAnswerService;
    private final UserMemoryService manageMemory;
    private final UserChangeService readChanges;
    private final PersonalSpaceRevertService manageReverts;
    private final AiChatService readChat;
    private final UploadProperties readUploadProperties;

    public PersonalSpaceResponse readSpace(Long userId) {
        UserResponse readAccount = requireAccount(userId);
        UserProfileResponse readProfile = readProfileService.getProfile(userId);
        ResumeResponse readResume = readResumeService.getResume(userId);
        QuestionnaireApplicationCount readApplications = readAnswerService.countMyApplications(userId);
        UserMemoryResponse readMemory = manageMemory.readMemory(userId);
        AiSessionResponse readSession = readChat.readSession(userId);
        String readDisplayName = readAccount.getRealName() == null
                ? "未填写姓名" : readAccount.getRealName();

        return new PersonalSpaceResponse(
                new PersonalSpaceResponse.AccountSummary(
                        readAccount.getId(),
                        readAccount.getUsername(),
                        readDisplayName,
                        readAccount.getStudentId(),
                        readAccount.getRole(),
                        readAccount.getStatus()),
                new PersonalSpaceResponse.Sections(
                        new PersonalSpaceResponse.ResourceSummary(
                                readProfile != null,
                                readProfile == null ? null : readProfile.getVersion(),
                                readProfile == null ? null : readProfile.getUpdatedAt(),
                                PROFILE_HREF),
                        new PersonalSpaceResponse.ResourceSummary(
                                readResume != null,
                                readResume == null ? null : readResume.getVersion(),
                                readResume == null ? null : readResume.getUpdatedAt(),
                                RESUME_HREF),
                        new PersonalSpaceResponse.DocumentSummary(
                                readFileService.countByUser(userId),
                                readFileService.getUsedBytes(userId),
                                readUploadProperties.getQuotaPerUser(),
                                DOCUMENTS_HREF),
                        new PersonalSpaceResponse.ApplicationSummary(
                                readApplications.getTotal(),
                                readApplications.getDraft(),
                                readApplications.getPending(),
                                readApplications.getDone(),
                                APPLICATIONS_HREF),
                        new PersonalSpaceResponse.MemorySummary(
                                readMemory.entries().size(),
                                readMemory.version(),
                                readMemory.updatedAt(),
                                MEMORY_HREF),
                        new PersonalSpaceResponse.AssistantSummary(
                                readSession.exists(),
                                readSession.activeRun(),
                                ASSISTANT_HREF)));
    }

    public UserProfileResponse readProfile(Long userId) {
        requireAccount(userId);
        return readProfileService.getProfile(userId);
    }

    public ResumeResponse readResume(Long userId) {
        requireAccount(userId);
        return readResumeService.getResume(userId);
    }

    public PersonalSpaceDocumentsResponse readDocuments(Long userId) {
        requireAccount(userId);
        return loadDocuments(userId);
    }

    public PersonalSpaceDocumentsResponse readDeletedDocuments(Long userId) {
        requireAccount(userId);
        return new PersonalSpaceDocumentsResponse(
                readFileService.listDeletedByUser(userId),
                readFileService.getUsedBytes(userId),
                readUploadProperties.getQuotaPerUser());
    }

    @Transactional
    public void deleteDocument(Long userId, Long fileId) {
        requireAccount(userId);
        readFileService.delete(userId, fileId);
    }

    @Transactional
    public void restoreDocument(Long userId, Long fileId) {
        requireAccount(userId);
        readFileService.restore(userId, fileId);
    }

    private PersonalSpaceDocumentsResponse loadDocuments(Long userId) {
        List<ResumeFileResponse> readFiles = readFileService.listByUser(userId);
        return new PersonalSpaceDocumentsResponse(
                readFiles,
                readFileService.getUsedBytes(userId),
                readUploadProperties.getQuotaPerUser());
    }

    public MyQuestionnaireListPageResponse readApplications(
            Long userId,
            int page,
            int size,
            QuestionnaireSubmissionStatus status) {
        requireAccount(userId);
        return readAnswerService.listMyByUserId(userId, page, size, status);
    }

    public UserMemoryResponse readMemory(Long userId) {
        requireAccount(userId);
        return manageMemory.readMemory(userId);
    }

    @Transactional
    public UserMemoryResponse saveMemory(
            Long userId,
            String key,
            UserMemoryUpdateRequest request) {
        requireAccount(userId);
        return manageMemory.saveMemory(userId, key, request);
    }

    @Transactional
    public UserMemoryResponse deleteMemory(Long userId, String key, Long expectedVersion) {
        requireAccount(userId);
        return manageMemory.deleteMemory(userId, key, expectedVersion);
    }

    @Transactional
    public UserMemoryResponse clearMemory(Long userId, Long expectedVersion) {
        requireAccount(userId);
        return manageMemory.clearMemory(userId, expectedVersion);
    }

    public UserChangeActionPageResponse readActions(
            Long userId,
            Long beforeId,
            int size) {
        requireAccount(userId);
        return readChanges.readActions(userId, beforeId, size);
    }

    public UserChangeActionResponse readAction(Long userId, Long actionId) {
        requireAccount(userId);
        return readChanges.readAction(userId, actionId);
    }

    public UserChangeConfirmationResponse readConfirmation(Long userId, Long actionId) {
        requireAccount(userId);
        return readChanges.readConfirmation(userId, actionId);
    }

    @Transactional
    public UserChangeActionResponse createRevert(Long userId, Long actionId) {
        requireAccount(userId);
        return manageReverts.createRevert(userId, actionId);
    }

    @Transactional
    public UserChangeActionResponse createResolvedRevert(
            Long userId,
            Long actionId,
            UserChangeRevertResolveRequest request) {
        requireAccount(userId);
        return manageReverts.createResolvedRevert(userId, actionId, request);
    }

    @Transactional
    public UserChangeActionResponse confirmAction(Long userId, Long actionId) {
        requireAccount(userId);
        return manageReverts.confirmAction(userId, actionId);
    }

    @Transactional
    public UserChangeActionResponse rejectAction(Long userId, Long actionId) {
        requireAccount(userId);
        return manageReverts.rejectAction(userId, actionId);
    }

    private UserResponse requireAccount(Long userId) {
        UserResponse readAccount = readUserService.getUserById(userId);
        if (readAccount == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        return readAccount;
    }
}
