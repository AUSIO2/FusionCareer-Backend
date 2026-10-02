package com.fusioncareer.dto.req;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeParseRequest {
    private Long userId;
    private Long fileId;
}
