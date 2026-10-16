package com.fusioncareer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 用户变更快照加密配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "user-change")
public class UserChangeProperties {

    private short keyVersion = 1;

    /** 当前密钥，Base64 编码的 32 字节 AES-256 key。 */
    private String key = "";

    /** 可选轮换密钥，格式为 version=base64,version=base64。 */
    private String keys = "";
}
