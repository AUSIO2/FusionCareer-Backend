package com.fusioncareer.config;

import cn.dev33.satoken.stp.StpUtil;
import com.fusioncareer.entity.ResumeFileEntity;
import com.fusioncareer.exception.ResultCode;
import com.fusioncareer.exception.ServiceException;
import com.fusioncareer.service.ResumeFileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/** Apply the same ownership/role restriction to legacy static resume links. */
@Component
@RequiredArgsConstructor
public class ResumeFileAccessInterceptor implements HandlerInterceptor {
    private final ResumeFileService resumeFileService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        StpUtil.checkLogin();
        String prefix = request.getContextPath() + "/files/";
        String path = UriUtils.decode(request.getRequestURI().substring(prefix.length()), StandardCharsets.UTF_8);
        ResumeFileEntity file = resumeFileService.lambdaQuery()
                .eq(ResumeFileEntity::getStoragePath, path).one();
        if (file == null) {
            throw ServiceException.of(ResultCode.NOT_FOUND, "简历文件不存在");
        }
        if (!file.getUserId().equals(StpUtil.getLoginIdAsLong())) {
            StpUtil.checkRole("SUPERADMIN");
        }
        response.setHeader("Cache-Control", "no-store");
        return true;
    }
}
