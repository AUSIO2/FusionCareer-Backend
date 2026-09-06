package com.fusioncareer;

import com.fusioncareer.client.PythonServiceClient;
import com.fusioncareer.dto.req.ResumeRequest;
import com.fusioncareer.dto.req.UserProfileRequest;
import com.fusioncareer.dto.res.ResumeFileResponse;
import com.fusioncareer.dto.res.ResumeParseResponse;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.enums.Gender;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.ResumeFileService;
import com.fusioncareer.service.ResumeParseService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResumeParseTest {

    @Autowired
    private ResumeParseService updateParseService;

    @Autowired
    private ResumeFileService createFileService;

    @Autowired
    private UserProfileService updateProfileService;

    @Autowired
    private ResumeService updateResumeService;

    @Autowired
    private UserService createUserService;

    @MockBean
    private PythonServiceClient readPythonClient;

    @Test
    void updateResume() {
        UserEntity createUser = createUser("parse-user");
        UserProfileRequest createProfile = new UserProfileRequest();
        createProfile.setPhone("13800000000");
        updateProfileService.saveOrUpdateProfile(createUser.getId(), createProfile);
        ResumeRequest createResume = new ResumeRequest();
        createResume.setEducation("原教育经历");
        updateResumeService.saveOrUpdateResume(createUser.getId(), createResume);
        ResumeFileResponse createFile = createFileService.upload(createUser.getId(),
                new MockMultipartFile("file", "resume.pdf", "application/pdf", "%PDF".getBytes()));

        UserProfileRequest updateProfile = new UserProfileRequest();
        updateProfile.setRealName("解析用户");
        updateProfile.setGender(Gender.FEMALE);
        updateProfile.setPhone("");
        ResumeRequest updateResume = new ResumeRequest();
        updateResume.setEducation("");
        updateResume.setSkills("Python");
        ResumeParseResponse readResponse = new ResumeParseResponse();
        readResponse.setProfilePatch(updateProfile);
        readResponse.setResumePatch(updateResume);
        when(readPythonClient.parseResume(any())).thenReturn(readResponse);

        var readResult = updateParseService.updateResume(createUser.getId(), createFile.getId());

        assertThat(readResult.getUpdatedProfileFields()).containsExactlyInAnyOrder("realName", "gender");
        assertThat(readResult.getUpdatedResumeFields()).containsExactly("skills");
        assertThat(updateProfileService.getProfile(createUser.getId()).getRealName()).isEqualTo("解析用户");
        assertThat(updateProfileService.getProfile(createUser.getId()).getPhone()).isEqualTo("13800000000");
        assertThat(updateResumeService.getResume(createUser.getId()).getEducation()).isEqualTo("原教育经历");
        assertThat(updateResumeService.getResume(createUser.getId()).getSkills()).isEqualTo("Python");
    }

    @Test
    void rejectResume() {
        UserEntity createOwner = createUser("parse-owner");
        UserEntity createOther = createUser("parse-other");
        ResumeFileResponse createFile = createFileService.upload(createOwner.getId(),
                new MockMultipartFile("file", "resume.pdf", "application/pdf", "%PDF".getBytes()));

        assertThatThrownBy(() -> updateParseService.updateResume(createOther.getId(), createFile.getId()))
                .isInstanceOf(ServiceException.class);
    }

    private UserEntity createUser(String createStudentId) {
        UserEntity createUser = new UserEntity();
        createUser.setUsername(createStudentId);
        createUser.setStudentId(createStudentId);
        createUser.setRole(UserRole.NORMAL);
        createUser.setStatus(UserStatus.NORMAL);
        createUserService.save(createUser);
        return createUser;
    }
}
