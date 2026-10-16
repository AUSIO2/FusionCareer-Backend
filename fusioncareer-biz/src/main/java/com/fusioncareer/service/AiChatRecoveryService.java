package com.fusioncareer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fusioncareer.entity.AiSessionEntity;
import com.fusioncareer.mapper.AiSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 定时回收因进程崩溃遗留的过期 AI 运行租约。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatRecoveryService {

    private static final int BATCH_SIZE = 100;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AiSessionMapper sessionMapper;
    private final AiChatService chatService;

    @Scheduled(
            initialDelayString = "${ai-chat.recovery-initial-delay-ms:60000}",
            fixedDelayString = "${ai-chat.recovery-delay-ms:60000}")
    public void recoverExpiredRuns() {
        List<AiSessionEntity> readSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<AiSessionEntity>()
                        .isNotNull(AiSessionEntity::getActiveRunId)
                        .isNotNull(AiSessionEntity::getLeaseUntil)
                        .lt(AiSessionEntity::getLeaseUntil, LocalDateTime.now(SHANGHAI))
                        .orderByAsc(AiSessionEntity::getLeaseUntil)
                        .last("LIMIT " + BATCH_SIZE));
        for (AiSessionEntity readSession : readSessions) {
            try {
                chatService.recoverExpiredRun(
                        readSession.getUserId(),
                        readSession.getEpoch(),
                        readSession.getActiveRunId());
            } catch (RuntimeException readError) {
                log.warn("AI 过期运行回收失败, userId={}", readSession.getUserId());
            }
        }
    }
}
