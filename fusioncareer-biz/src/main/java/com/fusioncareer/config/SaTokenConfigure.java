package com.fusioncareer.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器，开启注解式鉴权功能
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/**",          // Auth 测试相关接口放行
                        "/api/auth/**",      // 实际的授权回调接口放行
                        "/internal/**",      // 内部系统调用放行
                        "/api/sys/health",   // 健康检查放行
                        "/error"             // Spring Boot 默认错误页放行
                );
    }
}
