package com.fusioncareer;

import com.fusioncareer.common.PageResult;
import com.fusioncareer.dto.req.UserRequest;
import com.fusioncareer.dto.res.UserResponse;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaginationTest {

    @Autowired
    private UserService readUserService;

    @BeforeEach
    void createUsers() {
        createUser("1");
        createUser("2");
        createUser("3");
    }

    @Test
    void readPage() {
        PageResult<UserResponse> readPage = readUserService.listUsers(1, 1, null);

        assertThat(readPage.getList()).hasSize(1);
        assertThat(readPage.getTotal()).isEqualTo(3);
        assertThat(readPage.getPage()).isEqualTo(1);
        assertThat(readPage.getSize()).isEqualTo(1);
        assertThat(readPage.getTotalPages()).isEqualTo(3);
    }

    @Test
    void normalizePage() {
        PageResult<UserResponse> readPage = readUserService.listUsers(0, 0, null);

        assertThat(readPage.getList()).hasSize(1);
        assertThat(readPage.getPage()).isEqualTo(1);
        assertThat(readPage.getSize()).isEqualTo(1);
    }

    @Test
    void limitPage() {
        PageResult<UserResponse> readPage = readUserService.listUsers(1, 1000, null);

        assertThat(readPage.getList()).hasSize(3);
        assertThat(readPage.getSize()).isEqualTo(100);
    }

    private void createUser(String createSuffix) {
        UserRequest createUser = new UserRequest();
        createUser.setUsername("pagination-user-" + createSuffix);
        createUser.setStudentId("pagination-student-" + createSuffix);
        createUser.setRole(UserRole.NORMAL);
        createUser.setStatus(UserStatus.NORMAL);
        readUserService.createUser(createUser);
    }
}
