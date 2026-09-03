package com.fusioncareer.service.impl;

import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.client.dto.ResumeParseResponse;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.enums.EduLevel;
import com.fusioncareer.enums.Gender;
import com.fusioncareer.enums.PoliticalStatus;
import com.fusioncareer.enums.ProfileUpdateStatus;
import com.fusioncareer.service.ProfileSyncOutcome;
import com.fusioncareer.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeProfileSyncServiceImplTest {

    @Mock
    private PythonServiceClient pythonServiceClient;

    @Mock
    private UserProfileService userProfileService;

    private ResumeProfileSyncServiceImpl service;
    private Resource resumeFile;

    @BeforeEach
    void setUp() {
        service = new ResumeProfileSyncServiceImpl(pythonServiceClient, userProfileService);
        resumeFile = new ByteArrayResource("resume".getBytes()) {
            @Override
            public String getFilename() {
                return "resume.pdf";
            }
        };
    }

    @Test
    void syncProfileMapsRecognizedFieldsAndSkipsBlankValues() {
        ResumeParseResponse.ResumeProfileData data = new ResumeParseResponse.ResumeProfileData();
        data.setRealName("  张三  ");
        data.setGender(1);
        data.setBirthDate("2000-01-15");
        data.setPoliticalStatus(3);
        data.setPhone("13800000000");
        data.setEmail("   ");
        data.setGrade("2022级");
        data.setEduLevel(4);
        ResumeParseResponse response = successResponse(data);
        when(pythonServiceClient.parseResume(resumeFile)).thenReturn(response);

        ProfileSyncOutcome outcome = service.syncProfile(42L, resumeFile);

        assertThat(outcome.status()).isEqualTo(ProfileUpdateStatus.SUCCESS);
        assertThat(outcome.updatedFields()).containsExactly(
                "realName", "gender", "birthDate", "politicalStatus", "phone", "grade", "eduLevel"
        );
        ArgumentCaptor<UserProfileRequest> captor = ArgumentCaptor.forClass(UserProfileRequest.class);
        verify(userProfileService).saveOrUpdateProfile(eq(42L), captor.capture());
        UserProfileRequest request = captor.getValue();
        assertThat(request.getRealName()).isEqualTo("张三");
        assertThat(request.getGender()).isEqualTo(Gender.MALE);
        assertThat(request.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 15));
        assertThat(request.getPoliticalStatus()).isEqualTo(PoliticalStatus.PARTY_MEMBER);
        assertThat(request.getPhone()).isEqualTo("13800000000");
        assertThat(request.getEmail()).isNull();
        assertThat(request.getEduLevel()).isEqualTo(EduLevel.DOCTORAL);
    }

    @Test
    void syncProfileDoesNotWriteWhenNoSupportedFieldsAreRecognized() {
        ResumeParseResponse.ResumeProfileData data = new ResumeParseResponse.ResumeProfileData();
        data.setBirthDate("2000-01");
        data.setGender(99);
        ResumeParseResponse response = successResponse(data);
        when(pythonServiceClient.parseResume(resumeFile)).thenReturn(response);

        ProfileSyncOutcome outcome = service.syncProfile(42L, resumeFile);

        assertThat(outcome.status()).isEqualTo(ProfileUpdateStatus.NO_FIELDS_RECOGNIZED);
        assertThat(outcome.updatedFields()).isEmpty();
        verify(userProfileService, never()).saveOrUpdateProfile(eq(42L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncProfileReturnsFailureWithoutWritingWhenAlgorithmCallFails() {
        when(pythonServiceClient.parseResume(resumeFile)).thenThrow(new RuntimeException("timeout"));

        ProfileSyncOutcome outcome = service.syncProfile(42L, resumeFile);

        assertThat(outcome.status()).isEqualTo(ProfileUpdateStatus.ALGORITHM_FAILED);
        assertThat(outcome.updatedFields()).isEmpty();
        verify(userProfileService, never()).saveOrUpdateProfile(eq(42L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncProfileDistinguishesProfileWriteFailure() {
        ResumeParseResponse.ResumeProfileData data = new ResumeParseResponse.ResumeProfileData();
        data.setRealName("张三");
        when(pythonServiceClient.parseResume(resumeFile)).thenReturn(successResponse(data));
        doThrow(new RuntimeException("database unavailable"))
                .when(userProfileService)
                .saveOrUpdateProfile(eq(42L), org.mockito.ArgumentMatchers.any());

        ProfileSyncOutcome outcome = service.syncProfile(42L, resumeFile);

        assertThat(outcome.status()).isEqualTo(ProfileUpdateStatus.PROFILE_UPDATE_FAILED);
        assertThat(outcome.updatedFields()).isEmpty();
    }

    private ResumeParseResponse successResponse(ResumeParseResponse.ResumeProfileData data) {
        ResumeParseResponse response = new ResumeParseResponse();
        response.setCode(200);
        response.setMessage("success");
        response.setData(data);
        return response;
    }
}
