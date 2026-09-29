package com.fusioncareer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.common.R;
import com.fusioncareer.dto.req.AiMessageRequest;
import com.fusioncareer.dto.res.AiMessagePageResponse;
import com.fusioncareer.dto.res.AiSessionResponse;
import com.fusioncareer.service.AiChatService;
import com.fusioncareer.service.AiStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SaCheckLogin
@RestController
@RequestMapping("/personal-space/assistant")
@RequiredArgsConstructor
@Tag(name = "个人空间 AI 助手", description = "当前用户唯一 Assistant Session 与消息")
public class PersonalSpaceAssistantController {

    private final AiChatService manageChat;
    private final AiStreamService manageStream;

    @GetMapping("/session")
    @Operation(summary = "获取当前用户唯一 AI Session 状态")
    public R<AiSessionResponse> readSession() {
        return R.success(manageChat.readSession(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/messages")
    @Operation(summary = "分页获取当前 epoch 的 AI 消息")
    public R<AiMessagePageResponse> readMessages(
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int size) {
        return R.success(manageChat.readMessages(
                StpUtil.getLoginIdAsLong(), beforeId, size));
    }

    @PostMapping(value = "/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    @Operation(summary = "发送消息并流式获取 AI 回答")
    public SseEmitter streamMessage(
            @Valid @RequestBody AiMessageRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return manageStream.streamMessage(StpUtil.getLoginIdAsLong(), request);
    }

    @PostMapping("/run/cancel")
    @Operation(summary = "取消当前 AI 运行")
    public R<AiSessionResponse> cancelRun() {
        return R.success(manageStream.cancelRun(StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/session/clear")
    @Operation(summary = "清空当前对话，保留 Memory 与变更历史")
    public R<AiSessionResponse> clearSession() {
        return R.success(manageStream.clearSession(StpUtil.getLoginIdAsLong()));
    }

    @DeleteMapping("/session")
    @Operation(summary = "重置 AI Session，保留 Memory 与变更历史")
    public R<AiSessionResponse> resetSession() {
        return R.success(manageStream.resetSession(StpUtil.getLoginIdAsLong()));
    }
}
