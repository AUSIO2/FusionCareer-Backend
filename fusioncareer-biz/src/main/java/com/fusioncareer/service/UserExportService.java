package com.fusioncareer.service;

import com.fusioncareer.enums.UserRole;
import java.util.List;

public interface UserExportService {
    byte[] exportUsers(String username, UserRole role, List<Long> userIds);
}
