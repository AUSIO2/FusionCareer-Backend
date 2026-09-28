package com.fusioncareer;

import com.fusioncareer.config.UploadProperties;
import com.fusioncareer.service.impl.FileStorageServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;

class FileStorageServiceImplTest {

    @Test
    void acceptDocxUsedByResumeParserEngine() {
        var service = new FileStorageServiceImpl(new UploadProperties());
        var file = new MockMultipartFile(
                "file",
                "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "PK\u0003\u0004".getBytes());

        assertThatCode(() -> service.validate(file)).doesNotThrowAnyException();
    }
}
