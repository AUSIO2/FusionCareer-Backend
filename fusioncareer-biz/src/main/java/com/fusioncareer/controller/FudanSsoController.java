package com.fusioncareer.controller;

import com.fusioncareer.common.R;
import com.fusioncareer.enums.UserRole;
import com.fusioncareer.service.FudanSsoService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * 复旦 SSO 统一身份认证接口
 *
 * @author Xiong Heng
 */
@Slf4j
@RestController
@RequestMapping("/fudan")
@RequiredArgsConstructor
@Tag(name = "复旦SSO接口", description = "复旦 SSO 统一身份认证接口")
public class FudanSsoController {

    private static final String SSO_STATE = "FUDAN_SSO_STATE";

    private final FudanSsoService fudanSsoService;

    /**
     * 主动发起登录：重定向至 Fudan 统一认证中心
     */
    @GetMapping("/login")
    @Operation(summary = "主动发起登录")
    public RedirectView login(
            @RequestParam(name = "role", required = false) UserRole readRole,
            HttpSession updateSession) {
        if (fudanSsoService.useMockLogin()) {
            log.warn("Using dev mock login");
            return new RedirectView(fudanSsoService.loginMock(readRole));
        }
        String createState = fudanSsoService.createState();
        updateSession.setAttribute(SSO_STATE, createState);
        String readUrl = fudanSsoService.buildLoginUrl(createState);
        log.info("Redirecting to Fudan SSO login");
        return new RedirectView(readUrl);
    }

    /**
     * 认证回调地址：获取授权码并换取 Token 与用户信息
     */
    @GetMapping("/callback")
    @Operation(summary = "认证回调地址")
    public RedirectView callback(@RequestParam("code") String readCode,
                                 @RequestParam("state") String readActualState,
                                 HttpSession updateSession) {
        String readExpectedState = (String) updateSession.getAttribute(SSO_STATE);
        updateSession.removeAttribute(SSO_STATE);
        try {
            fudanSsoService.verifyState(readExpectedState, readActualState);
            String readUrl = fudanSsoService.processCallback(readCode);
            log.info("Fudan SSO login completed");
            return new RedirectView(readUrl);
        } catch (Exception e) {
            log.warn("Fudan SSO login rejected");
            return new RedirectView("/error?msg=sso_login_failed");
        }
    }

    /**
     * 主动发起退出：注销本地会话并重定向至 Fudan 统一退出地址
     */
    @GetMapping("/logout")
    @Operation(summary = "主动发起退出")
    public RedirectView logout() {
        String readUrl = fudanSsoService.processLogout();
        log.info("Redirecting to Fudan SSO logout");
        return new RedirectView(readUrl);
    }

    @PostMapping("/logout")
    @Operation(summary = "注销本地会话并返回复旦统一退出地址")
    public R<Map<String, String>> logoutSession() {
        return R.success(Map.of("redirectUrl", fudanSsoService.processLogout()));
    }

    /**
     * 被动跟随退出：Fudan 认证中心后端通知应用注销会话
     */
    @GetMapping("/slo")
    @Operation(summary = "被动跟随退出")
    public R<Void> slo(@RequestParam("token") String readToken) {
        log.info("Received Fudan SSO logout request");
        fudanSsoService.processSlo(readToken);
        return R.success();
    }
}
