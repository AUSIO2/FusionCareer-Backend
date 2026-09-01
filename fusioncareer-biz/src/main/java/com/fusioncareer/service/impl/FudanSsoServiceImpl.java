package com.fusioncareer.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.config.FudanOAuth2Properties;
import com.fusioncareer.entity.ResumeEntity;
import com.fusioncareer.entity.UserEntity;
import com.fusioncareer.entity.UserProfileEntity;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.FudanSsoService;
import com.fusioncareer.service.ResumeService;
import com.fusioncareer.service.UserProfileService;
import com.fusioncareer.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class FudanSsoServiceImpl implements FudanSsoService {

    private final FudanOAuth2Properties ssoProperties;
    private final UserService userService;
    private final UserProfileService userProfileService;
    private final ResumeService resumeService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    // 在内存中映射 Fudan 的 access_token -> Sa-Token 的 tokenValue
    private final Map<String, String> ssoToSaTokenMap = new ConcurrentHashMap<>();

    @Override
    public String createState() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void verifyState(String readExpected, String readActual) {
        if (readExpected == null || readActual == null || !MessageDigest.isEqual(
                readExpected.getBytes(StandardCharsets.UTF_8),
                readActual.getBytes(StandardCharsets.UTF_8))) {
            throw ServiceException.of(ResultCode.VALIDATE_FAILED, "SSO state 无效或已使用");
        }
    }

    @Override
    public String buildLoginUrl(String readState) {
        return UriComponentsBuilder.fromUriString(ssoProperties.getAuthUrl())
                .queryParam("client_id", ssoProperties.getClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", ssoProperties.getRedirectUri())
                .queryParam("state", readState)
                .build()
                .encode()
                .toUriString();
    }

    @Override
    public String processCallback(String code) {
        try {
            // 1. 获取 Access Token
            String accessToken = getAccessToken(code);
            if (accessToken == null) {
                throw new RuntimeException("Failed to get access token");
            }

            // 2. 获取用户信息（复旦可能返回 userId / sub / uid 等字段）
            JsonNode userInfo = getUserInfo(accessToken);
            String userId = resolveFudanUserId(userInfo);
            if (userId == null || userId.isBlank()) {
                log.error("Fudan userInfo missing user id");
                throw new RuntimeException("Failed to get user info");
            }

            String userName = resolveFudanUserName(userInfo, userId);

            // 3. 业务系统登录注册逻辑
            UserEntity user = userService.lambdaQuery().eq(UserEntity::getStudentId, userId).one();
            if (user == null) {
                // 首次登录，自动注册
                user = new UserEntity();
                user.setStudentId(userId);
                user.setUsername(userName);
                user.setRole(UserRole.NORMAL);
                user.setStatus(UserStatus.NORMAL);
                user.setCreatedAt(LocalDateTime.now());
                userService.save(user);

                // 同步复旦 UIS 用户资料到 UserProfile
                UserProfileEntity profile = new UserProfileEntity();
                profile.setUserId(user.getId());
                profile.setRealName(userName);
                if (userInfo.has("mobile")) {
                    profile.setPhone(userInfo.get("mobile").asText());
                }
                if (userInfo.has("email")) {
                    profile.setEmail(userInfo.get("email").asText());
                }
                if (userInfo.has("department")) {
                    profile.setMajor(userInfo.get("department").asText());
                }
                profile.setCreatedAt(LocalDateTime.now());
                userProfileService.save(profile);

                // 初始化空的简历记录
                ResumeEntity resume = new ResumeEntity();
                resume.setUserId(user.getId());
                resume.setCreatedAt(LocalDateTime.now());
                resumeService.save(resume);

                log.info("Registered new user from Fudan SSO");
            }

            // 4. Sa-Token 登录
            StpUtil.login(user.getId());
            String saTokenValue = StpUtil.getTokenValue();

            // 记录 ssoToken 和 saToken 的关系，供被动登出使用
            ssoToSaTokenMap.put(accessToken, saTokenValue);

            // 构造重定向 URL
            return ssoProperties.getFrontendRedirectUrl() + "#/login?token=" + saTokenValue;

        } catch (Exception e) {
            log.error("Fudan SSO callback failed: {}", e.getClass().getSimpleName());
            throw new RuntimeException("SSO login failed");
        }
    }

    @Override
    public String processLogout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        String redirectUrl = URLEncoder.encode(ssoProperties.getFrontendRedirectUrl(), StandardCharsets.UTF_8);
        return String.format("%s?redirectToLogin=false&redirectToUrl=%s",
                ssoProperties.getLogoutUrl(), redirectUrl);
    }

    @Override
    public void processSlo(String token) {
        String saTokenValue = ssoToSaTokenMap.remove(token);
        if (saTokenValue != null) {
            StpUtil.logoutByTokenValue(saTokenValue);
            log.info("Processed Fudan SSO logout");
        } else {
            log.warn("Fudan SSO token not found in local map");
        }
    }

    /**
     * 请求换取 Access Token
     */
    private String getAccessToken(String code) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String authString = ssoProperties.getClientId() + ":" + ssoProperties.getClientSecret();
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                ssoProperties.getTokenUrl(),
                HttpMethod.POST,
                request,
                String.class
        );

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("errcode")) {
                throw new RuntimeException("Error from Fudan Token API: " + root.get("msg").asText());
            }
            if (root.has("access_token")) {
                return root.get("access_token").asText();
            }
        }
        return null;
    }

    /**
     * 请求获取用户信息
     */
    private JsonNode getUserInfo(String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Bearer " + accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                ssoProperties.getUserInfoUrl(),
                HttpMethod.GET,
                request,
                String.class
        );

        String body = response.getBody();
        log.info("Fudan userInfo HTTP {}", response.getStatusCode());

        if (response.getStatusCode().is2xxSuccessful() && body != null && !body.isBlank()) {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("errcode") && root.get("errcode").asInt(0) != 0) {
                String msg = root.has("msg") ? root.get("msg").asText() : body;
                throw new RuntimeException("Error from Fudan UserInfo API: " + msg);
            }
            return root;
        }
        return null;
    }

    /** 兼容复旦 / OIDC 多种学号字段名 */
    private String resolveFudanUserId(JsonNode userInfo) {
        if (userInfo == null) {
            return null;
        }
        for (String field : new String[]{"userId", "sub", "uid", "username", "user_id"}) {
            if (userInfo.hasNonNull(field) && !userInfo.get(field).asText().isBlank()) {
                return userInfo.get(field).asText();
            }
        }
        if (userInfo.has("data") && userInfo.get("data").isObject()) {
            return resolveFudanUserId(userInfo.get("data"));
        }
        return null;
    }

    private String resolveFudanUserName(JsonNode userInfo, String fallback) {
        if (userInfo == null) {
            return fallback;
        }
        for (String field : new String[]{"userName", "name", "displayName", "nickname"}) {
            if (userInfo.hasNonNull(field) && !userInfo.get(field).asText().isBlank()) {
                return userInfo.get(field).asText();
            }
        }
        if (userInfo.has("data") && userInfo.get("data").isObject()) {
            String nested = resolveFudanUserName(userInfo.get("data"), fallback);
            if (!nested.equals(fallback)) {
                return nested;
            }
        }
        return fallback;
    }
}
