package com.fusioncareer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.config.AgentContextProperties;
import com.fusioncareer.entity.AiSessionEntity;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.mapper.AiSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 Agent run 使用的短期 HMAC 能力凭证。
 */
@Service
@RequiredArgsConstructor
public class AgentContextService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AgentContextProperties readProperties;
    private final AiSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final Environment readEnvironment;

    @jakarta.annotation.PostConstruct
    void validateSecret() {
        String readSecret = readProperties.getSecret();
        if (StringUtils.hasText(readSecret)
                && readSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("AGENT_CONTEXT_SECRET 至少需要 32 字节");
        }
        if (!StringUtils.hasText(readSecret)
                && readEnvironment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("生产环境必须配置 AGENT_CONTEXT_SECRET");
        }
    }

    public String issueContext(
            Long readUserId,
            String readRunId,
            Long readEpoch,
            String readRequestId,
            List<String> readScopes) {
        requireSecret();
        long createIssuedAt = Instant.now().getEpochSecond();
        Map<String, Object> writeClaims = new LinkedHashMap<>();
        writeClaims.put("sub", readUserId.toString());
        writeClaims.put("run", readRunId);
        writeClaims.put("epoch", readEpoch);
        writeClaims.put("request", readRequestId);
        writeClaims.put("scopes", readScopes);
        writeClaims.put("iat", createIssuedAt);
        writeClaims.put("exp", createIssuedAt + readProperties.getTtlSeconds());
        try {
            String writePayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(writeClaims));
            String writeSignature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signPayload(writePayload));
            return writePayload + "." + writeSignature;
        } catch (java.io.IOException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "AgentContext 签发失败");
        }
    }

    public AgentContext verifyContext(String readToken, String readScope) {
        requireSecret();
        if (!StringUtils.hasText(readToken) || readToken.length() > 4096) {
            throw buildForbidden();
        }
        String[] readParts = readToken.split("\\.", 2);
        if (readParts.length != 2) {
            throw buildForbidden();
        }
        byte[] readSignature;
        try {
            readSignature = Base64.getUrlDecoder().decode(readParts[1]);
        } catch (IllegalArgumentException readError) {
            throw buildForbidden();
        }
        if (!MessageDigest.isEqual(signPayload(readParts[0]), readSignature)) {
            throw buildForbidden();
        }
        try {
            JsonNode readClaims = objectMapper.readTree(
                    Base64.getUrlDecoder().decode(readParts[0]));
            long readNow = Instant.now().getEpochSecond();
            long readIssuedAt = readClaims.path("iat").asLong(-1);
            long readExpiresAt = readClaims.path("exp").asLong(-1);
            if (readIssuedAt < 0 || readExpiresAt < readNow || readIssuedAt > readNow + 30) {
                throw buildForbidden();
            }
            Long readUserId = Long.valueOf(readClaims.path("sub").asText());
            Long readEpoch = readClaims.path("epoch").asLong();
            String readRunId = readClaims.path("run").asText();
            String readRequestId = readClaims.path("request").asText();
            List<String> readScopes = new ArrayList<>();
            readClaims.path("scopes").forEach(readNode -> readScopes.add(readNode.asText()));
            if (!readScopes.contains(readScope)) {
                throw buildForbidden();
            }
            AiSessionEntity readSession = sessionMapper.selectById(readUserId);
            if (readSession == null
                    || !Objects.equals(readSession.getEpoch(), readEpoch)
                    || !Objects.equals(readSession.getActiveRunId(), readRunId)
                    || readSession.getLeaseUntil() == null
                    || readSession.getLeaseUntil().isBefore(java.time.LocalDateTime.now())) {
                throw buildForbidden();
            }
            return new AgentContext(
                    readUserId, readRunId, readEpoch, readRequestId, List.copyOf(readScopes));
        } catch (ServiceException readError) {
            throw readError;
        } catch (RuntimeException | java.io.IOException readError) {
            throw buildForbidden();
        }
    }

    private byte[] signPayload(String readPayload) {
        try {
            Mac createMac = Mac.getInstance(HMAC_ALGORITHM);
            createMac.init(new SecretKeySpec(
                    readProperties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return createMac.doFinal(readPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR, "AgentContext 签名失败");
        }
    }

    private void requireSecret() {
        if (!StringUtils.hasText(readProperties.getSecret())) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "Agent Tool 当前未启用");
        }
    }

    private ServiceException buildForbidden() {
        return ServiceException.of(ResultCode.FORBIDDEN, "AgentContext 无效或权限不足");
    }

    public record AgentContext(
            Long userId,
            String runId,
            Long epoch,
            String requestId,
            List<String> scopes) {
    }
}
