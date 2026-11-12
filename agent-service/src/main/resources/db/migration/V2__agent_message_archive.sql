-- =============================================================================
--  V3 —— AI 消息归档表（日志冷热分离）
--
--  对应问题：「每轮对话日志很大，如何存储、审计和降冷？」
--
--  策略：
--    1. 热数据（agent_message）只保留最近 N 天，走在线查询；
--    2. 归档数据（agent_message_archive）结构完全一致，按时间索引，
--       仍可被审计查询命中 —— 降冷不等于删除，审计链不能断；
--    3. 归档动作由定时任务分批执行（避免一次搬几百万行把库拖垮），
--       每次 LIMIT 一批 + 事务提交，可随时中断、可重入。
-- =============================================================================

CREATE TABLE `agent_message_archive`
(
    `id`                BIGINT   NOT NULL,
    `conversation_id`   BIGINT   NOT NULL,
    `seq_no`            INT      NOT NULL,
    `role`              VARCHAR(16)  NOT NULL,
    `content`           LONGTEXT     DEFAULT NULL,
    `reasoning_content` LONGTEXT     DEFAULT NULL,
    `tool_calls_json`   TEXT         DEFAULT NULL,
    `tool_call_id`      VARCHAR(64)  DEFAULT NULL,
    `tool_name`         VARCHAR(64)  DEFAULT NULL,
    `turn_id`           VARCHAR(64)  DEFAULT NULL,
    `model`             VARCHAR(64)  DEFAULT NULL,
    `tokens_in`         INT      NOT NULL DEFAULT 0,
    `tokens_out`        INT      NOT NULL DEFAULT 0,
    `latency_ms`        BIGINT   NOT NULL DEFAULT 0,
    `first_token_ms`    BIGINT   NOT NULL DEFAULT 0,
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',
    `block_reason`      VARCHAR(255) DEFAULT NULL,
    `trace_id`          VARCHAR(64)  DEFAULT NULL,
    `create_time`       DATETIME NOT NULL,
    `archived_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_archive_conv` (`conversation_id`, `seq_no`),
    KEY `idx_archive_time` (`create_time`),
    KEY `idx_archive_trace` (`trace_id`),
    KEY `idx_archive_turn` (`turn_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 消息归档（冷数据）';
