package com.fusioncareer.service;

import java.util.List;

/** 问卷作答及简历导出服务。 */
public interface QuestionnaireExportService {

    default byte[] buildCsv(Long readJobId, List<Long> readAnswerIds) {
        return buildCsv(readJobId, readAnswerIds, null);
    }

    byte[] buildCsv(Long readJobId, List<Long> readAnswerIds, List<String> readContent);

    default byte[] buildZip(Long readJobId, List<Long> readAnswerIds) {
        return buildZip(readJobId, readAnswerIds, null);
    }

    byte[] buildZip(Long readJobId, List<Long> readAnswerIds, List<String> readContent);

    String sanitizeFilename(String readFilename);
}
