package com.fusioncareer.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.interceptor.SaInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;

/**
 * Sa-Token 拦截器配置
 * <p>
 * 注册 {@link SaInterceptor} 以开启注解式鉴权（如 @SaCheckLogin, @SaCheckRole 等）。
 * 拦截器本身只负责"激活注解识别"，不代表所有接口都需要登录。
 * 需要保护的接口通过在 Controller 方法上添加注解来声明。
 *
 * @author Xiong Heng
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    private final UserRoleProvider readRoleProvider;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Sa-Token 将 StpInterface 保存在 JVM 全局对象中；请求前绑定当前 Spring Context，
        // 避免同 JVM 多 Context（测试或嵌入式部署）互相污染角色查询。
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            @NonNull HttpServletRequest request,
                            @NonNull HttpServletResponse response,
                            @NonNull Object handler) {
                        SaManager.setStpInterface(readRoleProvider);
                        return true;
                    }
                })
                .addPathPatterns("/**")
                .order(-100);
        // 首次 REQUEST 已完成鉴权；SseEmitter 的 ASYNC redispatch 没有 Sa-Token
        // ThreadLocal 上下文，且属于同一个已鉴权请求，不应重复执行注解检查。
        SaInterceptor checkToken = new SaInterceptor();
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            @NonNull HttpServletRequest request,
                            @NonNull HttpServletResponse response,
                            @NonNull Object handler) throws Exception {
                        if (request.getDispatcherType() == DispatcherType.ASYNC) {
                            return true;
                        }
                        return checkToken.preHandle(request, response, handler);
                    }
                })
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/**",          // Auth 测试相关接口放行
                        "/api/auth/**",      // 实际的授权回调接口放行
                        "/internal/**",      // 内部系统调用放行
                        "/api/sys/health",   // 健康检查放行
                        "/error"             // Spring Boot 默认错误页放行
                )
                .order(0);
    }
}
