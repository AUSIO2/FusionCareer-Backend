package com.fusioncareer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusioncareer.config.UserChangeProperties;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 AES-256-GCM 保护个人空间变更快照。
 */
@Service
@RequiredArgsConstructor
public class ChangeSnapshotCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final UserChangeProperties readProperties;
    private final ObjectMapper objectMapper;
    private final Environment readEnvironment;
    private final SecureRandom createRandom = new SecureRandom();

    private Map<Short, SecretKeySpec> readKeys = Map.of();

    @PostConstruct
    void loadKeys() {
        Map<Short, SecretKeySpec> loadKeys = new LinkedHashMap<>();
        if (StringUtils.hasText(readProperties.getKeys())) {
            for (String readPair : readProperties.getKeys().split(",")) {
                String[] readParts = readPair.trim().split("=", 2);
                if (readParts.length != 2) {
                    throw new IllegalStateException("USER_CHANGE_KEYS 格式错误");
                }
                short readVersion;
                try {
                    readVersion = Short.parseShort(readParts[0].trim());
                } catch (NumberFormatException readError) {
                    throw new IllegalStateException("USER_CHANGE_KEYS 版本号错误");
                }
                if (readVersion <= 0 || loadKeys.containsKey(readVersion)) {
                    throw new IllegalStateException("USER_CHANGE_KEYS 版本号必须为正数且不能重复");
                }
                loadKeys.put(readVersion, decodeKey(readParts[1]));
            }
        }
        if (readProperties.getKeyVersion() <= 0) {
            throw new IllegalStateException("USER_CHANGE_KEY_VERSION 必须为正数");
        }
        if (StringUtils.hasText(readProperties.getKey())) {
            loadKeys.put(readProperties.getKeyVersion(), decodeKey(readProperties.getKey()));
        }
        if (!loadKeys.isEmpty() && !loadKeys.containsKey(readProperties.getKeyVersion())) {
            throw new IllegalStateException("当前 USER_CHANGE_KEY_VERSION 没有对应密钥");
        }
        if (loadKeys.isEmpty() && readEnvironment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("生产环境必须配置 USER_CHANGE_KEY 或 USER_CHANGE_KEYS");
        }
        readKeys = Map.copyOf(loadKeys);
    }

    public EncryptedSnapshot encryptSnapshot(Object writeSnapshot, String readAad) {
        short readVersion = readProperties.getKeyVersion();
        SecretKeySpec readKey = readKeys.get(readVersion);
        if (readKey == null) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "变更历史加密密钥未配置，个人空间写入已禁用");
        }

        byte[] writePlaintext = null;
        try {
            writePlaintext = objectMapper.writeValueAsBytes(writeSnapshot);
            byte[] writeIv = new byte[IV_BYTES];
            createRandom.nextBytes(writeIv);
            Cipher encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, readKey,
                    new GCMParameterSpec(TAG_BITS, writeIv));
            encryptCipher.updateAAD(readAad.getBytes(StandardCharsets.UTF_8));
            byte[] writeCiphertext = encryptCipher.doFinal(writePlaintext);
            byte[] writePayload = ByteBuffer.allocate(writeIv.length + writeCiphertext.length)
                    .put(writeIv)
                    .put(writeCiphertext)
                    .array();
            return new EncryptedSnapshot(writePayload, readVersion);
        } catch (GeneralSecurityException | java.io.IOException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "变更历史快照加密失败");
        } finally {
            if (writePlaintext != null) {
                Arrays.fill(writePlaintext, (byte) 0);
            }
        }
    }

    public Map<String, Object> decryptSnapshot(
            byte[] readPayload,
            short readVersion,
            String readAad) {
        SecretKeySpec readKey = readKeys.get(readVersion);
        if (readKey == null || readPayload == null || readPayload.length <= IV_BYTES + 16) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "变更历史快照无法解密");
        }

        byte[] readPlaintext = null;
        try {
            byte[] readIv = Arrays.copyOfRange(readPayload, 0, IV_BYTES);
            byte[] readCiphertext = Arrays.copyOfRange(readPayload, IV_BYTES, readPayload.length);
            Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE, readKey,
                    new GCMParameterSpec(TAG_BITS, readIv));
            decryptCipher.updateAAD(readAad.getBytes(StandardCharsets.UTF_8));
            readPlaintext = decryptCipher.doFinal(readCiphertext);
            return objectMapper.readValue(readPlaintext, new TypeReference<>() { });
        } catch (GeneralSecurityException | java.io.IOException readError) {
            throw ServiceException.of(ResultCode.INTERNAL_SERVER_ERROR,
                    "变更历史快照无法解密");
        } finally {
            if (readPlaintext != null) {
                Arrays.fill(readPlaintext, (byte) 0);
            }
        }
    }

    private SecretKeySpec decodeKey(String readEncodedKey) {
        byte[] readKey;
        try {
            readKey = Base64.getDecoder().decode(readEncodedKey.trim());
        } catch (IllegalArgumentException readError) {
            throw new IllegalStateException("用户变更密钥必须使用 Base64 编码");
        }
        if (readKey.length != KEY_BYTES) {
            Arrays.fill(readKey, (byte) 0);
            throw new IllegalStateException("用户变更密钥解码后必须为 32 字节");
        }
        SecretKeySpec createKey = new SecretKeySpec(readKey, "AES");
        Arrays.fill(readKey, (byte) 0);
        return createKey;
    }

    public record EncryptedSnapshot(byte[] payload, short keyVersion) {
    }
}
