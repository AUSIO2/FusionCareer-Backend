package com.fusioncareer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fusioncareer.entity.AiSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiSessionMapper extends BaseMapper<AiSessionEntity> {

    @Insert("INSERT IGNORE INTO fc_ai_session "
            + "(user_id, epoch, created_at, updated_at) "
            + "VALUES (#{readUserId}, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))")
    int createSession(@Param("readUserId") Long readUserId);

    @Update("UPDATE fc_ai_session SET active_run_id = #{updateRunId}, "
            + "lease_until = #{updateLeaseUntil}, last_message_at = CURRENT_TIMESTAMP(3), "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE user_id = #{updateUserId} AND epoch = #{readEpoch} "
            + "AND (active_run_id IS NULL OR lease_until < CURRENT_TIMESTAMP(3))")
    int acquireRun(
            @Param("updateUserId") Long updateUserId,
            @Param("readEpoch") Long readEpoch,
            @Param("updateRunId") String updateRunId,
            @Param("updateLeaseUntil") LocalDateTime updateLeaseUntil);

    @Update("UPDATE fc_ai_session SET lease_until = #{updateLeaseUntil}, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE user_id = #{updateUserId} AND epoch = #{readEpoch} "
            + "AND active_run_id = #{readRunId}")
    int renewRun(
            @Param("updateUserId") Long updateUserId,
            @Param("readEpoch") Long readEpoch,
            @Param("readRunId") String readRunId,
            @Param("updateLeaseUntil") LocalDateTime updateLeaseUntil);

    @Update("UPDATE fc_ai_session SET active_run_id = NULL, lease_until = NULL, "
            + "last_message_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE user_id = #{updateUserId} AND epoch = #{readEpoch} "
            + "AND active_run_id = #{readRunId}")
    int releaseRun(
            @Param("updateUserId") Long updateUserId,
            @Param("readEpoch") Long readEpoch,
            @Param("readRunId") String readRunId);
}
