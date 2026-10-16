package com.fusioncareer.dto.res;

import com.fusioncareer.enums.UserRole;
import com.fusioncareer.enums.UserStatus;

import java.time.LocalDateTime;

/**
 * 当前用户个人空间总览。
 */
public record PersonalSpaceResponse(
        AccountSummary account,
        Sections sections) {

    public record Sections(
            ResourceSummary profile,
            ResourceSummary resume,
            DocumentSummary documents,
            ApplicationSummary applications,
            MemorySummary memory,
            AssistantSummary assistant) {
    }

    public record AccountSummary(
            Long id,
            String username,
            String displayName,
            String studentId,
            UserRole role,
            UserStatus status) {
    }

    public record ResourceSummary(
            boolean exists,
            Long version,
            LocalDateTime updatedAt,
            String href) {
    }

    public record DocumentSummary(
            long count,
            long usedBytes,
            long quotaBytes,
            String href) {
    }

    public record ApplicationSummary(
            long total,
            long draft,
            long submitted,
            long reviewed,
            String href) {
    }

    public record MemorySummary(
            int count,
            Long version,
            LocalDateTime updatedAt,
            String href) {
    }

    public record AssistantSummary(
            boolean sessionExists,
            boolean activeRun,
            String href) {
    }
}
