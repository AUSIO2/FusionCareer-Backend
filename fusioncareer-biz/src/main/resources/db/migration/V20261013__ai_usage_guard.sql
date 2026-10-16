ALTER TABLE fc_ai_session
    ADD COLUMN usage_date DATE NULL COMMENT 'AI每日额度日期（Asia/Shanghai）' AFTER last_message_at,
    ADD COLUMN daily_usage_count INT NOT NULL DEFAULT 0 COMMENT '当日AI请求次数' AFTER usage_date,
    ADD COLUMN irrelevant_strike_count INT NOT NULL DEFAULT 0 COMMENT '连续不相关消息次数' AFTER daily_usage_count,
    ADD COLUMN ai_banned_until DATETIME(3) NULL COMMENT 'AI功能封禁截止时间' AFTER irrelevant_strike_count;
