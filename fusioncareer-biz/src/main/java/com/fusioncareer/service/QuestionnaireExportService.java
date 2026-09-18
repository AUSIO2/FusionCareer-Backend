package com.fusioncareer.service;

import java.util.List;

/** 问卷作答及简历导出服务。 */
public interface QuestionnaireExportService {

    byte[] buildCsv(Long readJobId, List<Long> readAnswerIds);

    byte[] buildZip(Long readJobId, List<Long> readAnswerIds);

    String sanitizeFilename(String readFilename);
}
