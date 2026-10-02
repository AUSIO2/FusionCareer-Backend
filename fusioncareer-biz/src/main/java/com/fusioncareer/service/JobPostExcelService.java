package com.fusioncareer.service;

import com.fusioncareer.dto.req.JobPostRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 岗位 Excel 导入与模板生成服务。 */
public interface JobPostExcelService {

    /** 兼容平台模板和学院提供的“岗位需求总表”。 */
    List<JobPostRequest> parse(MultipartFile readFile);

    byte[] buildTemplate();
}
