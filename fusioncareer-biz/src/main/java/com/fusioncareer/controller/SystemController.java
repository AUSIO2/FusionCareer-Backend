package com.fusioncareer.controller;

import com.fusioncareer.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 系统通用接口
 *
 * @author Xiong Heng
 */
@RestController
@RequestMapping("/sys")
@Tag(name = "系统接口", description = "系统通用接口")
public class SystemController {

    /**
     * 系统健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "系统健康检查")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", System.currentTimeMillis());
        return R.success(data, "FusionCareer Backend is running smoothly.");
    }

}
