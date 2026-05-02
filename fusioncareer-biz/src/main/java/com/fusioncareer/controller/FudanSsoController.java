package com.fusioncareer.controller;

import com.fusioncareer.common.R;
import com.fusioncareer.service.FudanSsoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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

    private final FudanSsoService fudanSsoService;

    /**
     * 主动发起登录：重定向至 Fudan 统一认证中心
     */
    @GetMapping("/login")
    @Operation(summary = "主动发起登录")
    public RedirectView login() {
        String url = fudanSsoService.buildLoginUrl();
        log.info("Redirecting to Fudan SSO login: {}", url);
        return new RedirectView(url);
    }

    /**
     * 认证回调地址：获取授权码并换取 Token 与用户信息
     */
    @GetMapping("/callback")
    @Operation(summary = "认证回调地址")
    public RedirectView callback(@RequestParam("code") String code,
                                 @RequestParam(value = "state", required = false) String state) {
        log.info("Received Fudan SSO callback with code: {}", code);
        try {
            String redirectUrl = fudanSsoService.processCallback(code);
            log.info("User logged in successfully, redirecting to {}", redirectUrl);
            return new RedirectView(redirectUrl);
        } catch (Exception e) {
            log.error("Fudan SSO callback error", e);
            // 发生异常时，可以重定向到前端的特定错误页面
            return new RedirectView("/error?msg=" + e.getMessage());
        }
    }

    /**
     * 主动发起退出：注销本地会话并重定向至 Fudan 统一退出地址
     */
    @GetMapping("/logout")
    @Operation(summary = "主动发起退出")
    public RedirectView logout() {
        String url = fudanSsoService.processLogout();
        log.info("Redirecting to Fudan SSO logout: {}", url);
        return new RedirectView(url);
    }

    /**
     * 被动跟随退出：Fudan 认证中心后端通知应用注销会话
     */
    @GetMapping("/slo")
    @Operation(summary = "被动跟随退出")
    public R<Void> slo(@RequestParam("token") String token) {
        log.info("Received Fudan SSO logout request for token: {}", token);
        fudanSsoService.processSlo(token);
        return R.success();
    }
}
