package com.fusioncareer.service;

import org.springframework.core.io.Resource;

public interface ResumeProfileSyncService {

    ProfileSyncOutcome syncProfile(Long userId, Resource resumeFile);
}
