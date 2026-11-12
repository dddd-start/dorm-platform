-- =============================================================================
--  V1 —— Agent 服务独立库表结构（agent_db）
--
--  职责边界：本库只存 AI 会话、消息、工具审计、用量、记忆、RAG 知识库。
--  业务数据（区域/用户/房源/申请/订单）不在本库，全部通过 dorm-service HTTP 获取。
-- =============================================================================

-- 10. AI 会话
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_conversation`
(
    `id`                BIGINT       NOT NULL,
    `conversation_no`   VARCHAR(32)  NOT NULL COMMENT '会话编号',
    `user_id`           BIGINT       NOT NULL,
    `title`             VARCHAR(128)          DEFAULT NULL COMMENT '标题（首轮自动生成）',
    `agent_type`        VARCHAR(16)  NOT NULL DEFAULT 'ROUTER' COMMENT '当前主导 Agent：ROUTER/ROOM/POLICY/APPLY/ORDER/OPS',
    `message_count`     INT          NOT NULL DEFAULT 0,
    `last_message_time` DATETIME              DEFAULT NULL,
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
    `summary`           TEXT                  DEFAULT NULL COMMENT '滚动摘要（长期记忆）',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_no` (`conversation_no`),
    KEY `idx_conv_user_time` (`user_id`, `last_message_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 会话';

-- -----------------------------------------------------------------------------
-- 11. AI 消息（唯一索引保证多轮不乱序：seq_no 由服务端按会话单调分配）
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_message`
(
    `id`              BIGINT       NOT NULL,
    `conversation_id` BIGINT       NOT NULL,
    `seq_no`          INT          NOT NULL COMMENT '会话内单调递增序号',
    `role`            VARCHAR(16)  NOT NULL COMMENT 'USER/ASSISTANT/TOOL/SYSTEM',
    `content`         LONGTEXT     DEFAULT NULL COMMENT '消息内容',
    `reasoning_content` LONGTEXT   DEFAULT NULL COMMENT '模型思考过程（与答案分离存储，可解释）',
    `tool_calls_json` TEXT                  DEFAULT NULL COMMENT '本轮产生的工具调用',
    `tool_call_id`    VARCHAR(64)           DEFAULT NULL COMMENT 'TOOL 角色对应的调用ID',
    `tool_name`       VARCHAR(64)           DEFAULT NULL,
    `turn_id`         VARCHAR(64)           DEFAULT NULL COMMENT '客户端轮次ID（幂等键）',
    `model`           VARCHAR(64)           DEFAULT NULL,
    `tokens_in`       INT          NOT NULL DEFAULT 0,
    `tokens_out`      INT          NOT NULL DEFAULT 0,
    `latency_ms`      BIGINT       NOT NULL DEFAULT 0,
    `first_token_ms`  BIGINT       NOT NULL DEFAULT 0 COMMENT '首字延迟',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED' COMMENT 'STREAMING/COMPLETED/INTERRUPTED/BLOCKED/FAILED',
    `block_reason`    VARCHAR(255)          DEFAULT NULL,
    `trace_id`        VARCHAR(64)           DEFAULT NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_conv_seq` (`conversation_id`, `seq_no`),
    KEY `idx_msg_conv_time` (`conversation_id`, `create_time`),
    KEY `idx_msg_turn` (`turn_id`),
    KEY `idx_msg_trace` (`trace_id`),
    KEY `idx_msg_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 消息';

-- -----------------------------------------------------------------------------
-- 12. AI 工具调用审计（全链路追踪 + 可回放）
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_tool_call`
(
    `id`                BIGINT       NOT NULL,
    `conversation_id`   BIGINT       NOT NULL,
    `message_id`        BIGINT                DEFAULT NULL COMMENT '所属助手消息',
    `turn_id`           VARCHAR(64)           DEFAULT NULL,
    `seq_in_turn`       INT          NOT NULL DEFAULT 0 COMMENT '轮内第几次调用',
    `tool_name`         VARCHAR(64)  NOT NULL,
    `arguments_json`    TEXT                  DEFAULT NULL,
    `result_json`       TEXT                  DEFAULT NULL,
    `success`           TINYINT      NOT NULL DEFAULT 1,
    `error_msg`         VARCHAR(500)          DEFAULT NULL,
    `permission_checked` TINYINT     NOT NULL DEFAULT 1 COMMENT '是否通过权限校验',
    `deduplicated`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否被去重守卫命中（避免重复调用）',
    `cost_ms`           BIGINT       NOT NULL DEFAULT 0,
    `trace_id`          VARCHAR(64)           DEFAULT NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tool_call_conv` (`conversation_id`, `create_time`),
    KEY `idx_tool_call_name` (`tool_name`, `create_time`),
    KEY `idx_tool_call_trace` (`trace_id`),
    KEY `idx_tool_call_turn` (`turn_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 工具调用审计';

-- -----------------------------------------------------------------------------
-- 13. AI 异步任务（执行超过 10 秒的任务异步化）
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_task`
(
    `id`              BIGINT       NOT NULL,
    `task_no`         VARCHAR(32)  NOT NULL,
    `user_id`         BIGINT       NOT NULL,
    `conversation_id` BIGINT                DEFAULT NULL,
    `task_type`       VARCHAR(32)  NOT NULL COMMENT 'MONTHLY_REPORT / BATCH_APPLY / OPS_DAILY_REPORT',
    `params_json`     TEXT                  DEFAULT NULL,
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCESS/FAILED/CANCELLED',
    `priority`        INT          NOT NULL DEFAULT 5 COMMENT '数值越小优先级越高',
    `progress`        INT          NOT NULL DEFAULT 0 COMMENT '0-100',
    `result_json`     TEXT                  DEFAULT NULL,
    `error_msg`       VARCHAR(500)          DEFAULT NULL,
    `queued_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `start_time`      DATETIME              DEFAULT NULL,
    `finish_time`     DATETIME              DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    KEY `idx_task_status_priority` (`status`, `priority`, `queued_time`),
    KEY `idx_task_user` (`user_id`, `queued_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 异步任务';

-- -----------------------------------------------------------------------------
-- 14. 人工干预待办（Agent 需要人工确认的操作）
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_pending_approval`
(
    `id`              BIGINT       NOT NULL,
    `approval_no`     VARCHAR(32)  NOT NULL,
    `conversation_id` BIGINT       NOT NULL,
    `turn_id`         VARCHAR(64)           DEFAULT NULL,
    `user_id`         BIGINT       NOT NULL COMMENT '发起人',
    `action_type`     VARCHAR(32)  NOT NULL COMMENT 'SUBMIT_APPLY / CANCEL_APPLY ...',
    `params_json`     TEXT                  DEFAULT NULL,
    `reason`          VARCHAR(255)          DEFAULT NULL,
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
    `approver_id`     BIGINT                DEFAULT NULL,
    `approve_time`    DATETIME              DEFAULT NULL,
    `remark`          VARCHAR(255)          DEFAULT NULL,
    `expire_time`     DATETIME              DEFAULT NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_approval_no` (`approval_no`),
    KEY `idx_approval_status` (`status`, `create_time`),
    KEY `idx_approval_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 人工确认待办';

-- -----------------------------------------------------------------------------
-- 15. AI 用量与成本（按天聚合，用于成本控制与看板）
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_usage_daily`
(
    `id`                BIGINT       NOT NULL,
    `stat_date`         DATE         NOT NULL,
    `user_id`           BIGINT       NOT NULL,
    `conversation_count` INT         NOT NULL DEFAULT 0,
    `turn_count`        INT          NOT NULL DEFAULT 0,
    `prompt_tokens`     BIGINT       NOT NULL DEFAULT 0,
    `completion_tokens` BIGINT       NOT NULL DEFAULT 0,
    `total_tokens`      BIGINT       NOT NULL DEFAULT 0,
    `tool_call_count`   INT          NOT NULL DEFAULT 0,
    `rag_hit_count`     INT          NOT NULL DEFAULT 0,
    `blocked_count`     INT          NOT NULL DEFAULT 0,
    `cache_hit_count`   INT          NOT NULL DEFAULT 0,
    `est_cost`          DECIMAL(12, 6) NOT NULL DEFAULT 0.000000,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_date_user` (`stat_date`, `user_id`),
    KEY `idx_usage_date` (`stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 用量与成本日聚合';

-- -----------------------------------------------------------------------------
-- 16. Agent 长期记忆
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_long_memory`
(
    `id`          BIGINT       NOT NULL,
    `user_id`     BIGINT       NOT NULL,
    `memory_type` VARCHAR(16)  NOT NULL COMMENT 'PROFILE-画像 SUMMARY-会话摘要 PREFERENCE-偏好',
    `memory_key`  VARCHAR(64)           DEFAULT NULL COMMENT '画像键，如 PREFERRED_ROOM_TYPE',
    `content`     TEXT         NOT NULL COMMENT '记忆内容',
    `importance`  INT          NOT NULL DEFAULT 5 COMMENT '重要度 1-10',
    `source_conversation_id` BIGINT DEFAULT NULL,
    `expire_time` DATETIME              DEFAULT NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_memory_user_type` (`user_id`, `memory_type`, `importance`),
    UNIQUE KEY `uk_memory_user_key` (`user_id`, `memory_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 长期记忆';

-- -----------------------------------------------------------------------------
-- 17. RAG 知识文档
-- -----------------------------------------------------------------------------
CREATE TABLE `rag_document`
(
    `id`           BIGINT       NOT NULL,
    `doc_code`     VARCHAR(64)  NOT NULL COMMENT '文档编码',
    `title`        VARCHAR(255) NOT NULL,
    `category`     VARCHAR(32)  NOT NULL COMMENT 'POLICY-政策 RULE-管理规定 REGION_GUIDE-属地须知 FAQ-常见问题',
    `region_id`    BIGINT                DEFAULT NULL COMMENT '归属区域（NULL 表示全局）',
    `version`      INT          NOT NULL DEFAULT 1 COMMENT '文档版本，变更后触发索引重建与缓存失效',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED/OFFLINE',
    `content`      LONGTEXT     NOT NULL COMMENT '正文（Markdown）',
    `source`       VARCHAR(255)          DEFAULT NULL COMMENT '来源说明',
    `effective_date` DATE               DEFAULT NULL COMMENT '生效日期',
    `chunk_count`  INT          NOT NULL DEFAULT 0,
    `indexed_version` INT       NOT NULL DEFAULT 0 COMMENT '已建索引的版本号',
    `create_by`    BIGINT                DEFAULT NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_code` (`doc_code`),
    KEY `idx_doc_category_status` (`category`, `status`),
    KEY `idx_doc_region` (`region_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 知识文档';

-- -----------------------------------------------------------------------------
-- 18. RAG 文档切片（含向量，向量以 float32 小端字节序存储）
-- -----------------------------------------------------------------------------
CREATE TABLE `rag_chunk`
(
    `id`             BIGINT       NOT NULL,
    `document_id`    BIGINT       NOT NULL,
    `doc_version`    INT          NOT NULL COMMENT '所属文档版本',
    `chunk_index`    INT          NOT NULL COMMENT '切片序号',
    `title`          VARCHAR(255)          DEFAULT NULL COMMENT '所属标题路径',
    `content`        TEXT         NOT NULL COMMENT '切片正文',
    `content_hash`   CHAR(64)     NOT NULL COMMENT '内容 SHA-256（用于缓存与去重）',
    `embedding`      MEDIUMBLOB            DEFAULT NULL COMMENT '向量（float32 小端）',
    `embedding_dim`  INT          NOT NULL DEFAULT 0,
    `embedding_model` VARCHAR(64)          DEFAULT NULL,
    `token_count`    INT          NOT NULL DEFAULT 0,
    `region_id`      BIGINT                DEFAULT NULL COMMENT '冗余：便于元数据过滤',
    `category`       VARCHAR(32)           DEFAULT NULL COMMENT '冗余：便于元数据过滤',
    `metadata_json`  VARCHAR(1000)         DEFAULT NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_doc_ver_idx` (`document_id`, `doc_version`, `chunk_index`),
    KEY `idx_chunk_hash` (`content_hash`),
    KEY `idx_chunk_region_category` (`region_id`, `category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 文档切片与向量';

-- -----------------------------------------------------------------------------
-- 19. RAG 检索日志（生产效果追踪：召回质量到底如何）
-- -----------------------------------------------------------------------------
CREATE TABLE `rag_retrieval_log`
(
    `id`              BIGINT       NOT NULL,
    `trace_id`        VARCHAR(64)           DEFAULT NULL,
    `conversation_id` BIGINT                DEFAULT NULL,
    `message_id`      BIGINT                DEFAULT NULL,
    `user_id`         BIGINT                DEFAULT NULL,
    `query_text`      VARCHAR(500) NOT NULL,
    `top_k`           INT          NOT NULL,
    `hit_count`       INT          NOT NULL DEFAULT 0 COMMENT '命中数（达到 minScore 的切片数）',
    `top_score`       DECIMAL(6, 4) NOT NULL DEFAULT 0.0000,
    `hit_chunk_ids`   VARCHAR(1000)         DEFAULT NULL COMMENT '命中的切片ID，逗号分隔',
    `scores`          VARCHAR(500)          DEFAULT NULL,
    `latency_ms`      BIGINT       NOT NULL DEFAULT 0,
    `cache_hit`       TINYINT      NOT NULL DEFAULT 0,
    `answer_used`     TINYINT      NOT NULL DEFAULT 1 COMMENT '答案是否引用了检索结果',
    `feedback`        VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/LIKE/DISLIKE',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rag_log_time` (`create_time`),
    KEY `idx_rag_log_user` (`user_id`, `create_time`),
    KEY `idx_rag_log_feedback` (`feedback`),
    KEY `idx_rag_log_trace` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 检索日志';

-- -----------------------------------------------------------------------------
-- 20. 用户对 AI 回答的反馈（用于质量闭环）
-- -----------------------------------------------------------------------------
CREATE TABLE `agent_feedback`
(
    `id`              BIGINT       NOT NULL,
    `conversation_id` BIGINT       NOT NULL,
    `message_id`      BIGINT       NOT NULL,
    `user_id`         BIGINT       NOT NULL,
    `rating`          VARCHAR(16)  NOT NULL COMMENT 'LIKE/DISLIKE',
    `reason`          VARCHAR(255)          DEFAULT NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_feedback_msg_user` (`message_id`, `user_id`),
    KEY `idx_feedback_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 回答反馈';
