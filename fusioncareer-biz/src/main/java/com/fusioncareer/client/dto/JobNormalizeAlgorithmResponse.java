package com.fusioncareer.client.dto;

import lombok.Data;

import java.util.Map;

/**
 * 算法服务的岗位标准化响应。
 *
 * data 暂以 Map 接收，待算法字段协议最终确认后只需调整适配层。
 */
@Data
public class JobNormalizeAlgorithmResponse {

    private Integer code;
    private String message;
    private Map<String, Object> data;
}
