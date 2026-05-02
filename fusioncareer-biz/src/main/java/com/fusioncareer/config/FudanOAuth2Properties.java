package com.fusioncareer.config;

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
}
