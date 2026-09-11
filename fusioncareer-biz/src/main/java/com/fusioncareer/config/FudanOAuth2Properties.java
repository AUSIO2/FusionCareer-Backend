package com.fusioncareer.config;

import com.fusioncareer.enums.UserRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "fudan-sso")
public class FudanOAuth2Properties {
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authUrl = "https://id.fudan.edu.cn/idp/authCenter/authenticate";
    private String tokenUrl = "https://id.fudan.edu.cn/idp/api/v3/oauth2/token";
    private String userInfoUrl = "https://id.fudan.edu.cn/idp/api/v3/oauth2/userInfo";
    private String logoutUrl = "https://id.fudan.edu.cn/idp/authCenter/GLO";
    private String frontendRedirectUrl = "http://localhost:3000";
    private boolean mockLogin;
    private String mockStudentId = "dev-student";
    private String mockUsername = "本地学生";
    private String mockAdminId = "dev-admin";
    private UserRole mockRole = UserRole.ADMIN;
}
