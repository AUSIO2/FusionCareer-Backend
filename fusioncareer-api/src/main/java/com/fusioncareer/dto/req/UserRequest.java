package com.fusioncareer.dto.req;

import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import lombok.Data;

@Data
public class UserRequest {
    private String username;
    private String studentId;
    private String password;
    private UserRole role;
    private UserStatus status;
}
