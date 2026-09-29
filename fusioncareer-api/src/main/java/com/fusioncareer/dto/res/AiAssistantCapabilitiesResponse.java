package com.fusioncareer.dto.res;

import java.util.List;

/** 当前部署的 Assistant 能力和客户端输入上限。 */
public record AiAssistantCapabilitiesResponse(
        boolean configured,
        boolean writeEnabled,
        List<String> readTools,
        List<String> writeTools,
        Limits limits) {

    public record Limits(
            int maxMessageChars,
            int maxAttachments,
            int maxHistoryMessages,
            int maxContextChars) {
    }
}
