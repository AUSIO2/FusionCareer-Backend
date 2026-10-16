-- Apply once before deploying the new Java message entity. Old clients ignore the new columns.
ALTER TABLE fc_ai_message
    ADD COLUMN input_metadata JSON NULL COMMENT '结构化用户输入，参与请求幂等',
    ADD COLUMN presentation JSON NULL COMMENT '版本化展示引用，不保存完整Tool结果';
