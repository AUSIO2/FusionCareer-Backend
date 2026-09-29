package com.fusioncareer.controller.internal;

import com.fusioncareer.common.R;
import com.fusioncareer.service.AgentToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/agent/tools")
@RequiredArgsConstructor
public class InternalAgentToolController {

    private final AgentToolService runTools;

    @PostMapping("/{toolName}")
    public R<Object> executeTool(
            @RequestHeader("X-Agent-Context") String contextToken,
            @RequestHeader(value = "X-Agent-Tool-Call-Id", required = false) String toolCallId,
            @PathVariable String toolName,
            @RequestBody(required = false) Map<String, Object> arguments) {
        return R.success(runTools.executeTool(
                contextToken, toolName, arguments, toolCallId));
    }
}
