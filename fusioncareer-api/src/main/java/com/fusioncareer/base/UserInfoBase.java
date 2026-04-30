package com.fusioncareer.base;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 * @author Xiong heng
 */
@Data
@SuperBuilder
@RequiredArgsConstructor
public class UserInfoBase implements Serializable {
    private String userId;
    private String userName;
}
