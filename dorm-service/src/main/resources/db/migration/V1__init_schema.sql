-- =============================================================================
--  V1 —— 海外宿舍管理系统 基础表结构
--
--  设计约定：
--    1. 不使用逻辑删除（避免与唯一索引冲突）；房间/床位的「下线」用 status 表达。
--    2. 主键统一为应用侧雪花 ID（BIGINT），避免自增暴露业务量级。
--    3. 所有金额使用 DECIMAL；所有时间使用 DATETIME（服务器时区 +08:00）。
--    4. version 字段用于 MyBatis-Plus 乐观锁（并发分配床位时防止超卖）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 区域（海外区域，如东南亚 / 欧洲 / 中东）
-- -----------------------------------------------------------------------------
CREATE TABLE `region`
(
    `id`           BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `code`         VARCHAR(32)  NOT NULL COMMENT '区域编码，如 SEA / EU / ME',
    `name`         VARCHAR(64)  NOT NULL COMMENT '区域名称',
    `name_en`      VARCHAR(64)           DEFAULT NULL COMMENT '英文名称',
    `country`      VARCHAR(64)           DEFAULT NULL COMMENT '主要国家',
    `country_code` VARCHAR(8)            DEFAULT NULL COMMENT 'ISO 国家代码',
    `timezone`     VARCHAR(64)           DEFAULT NULL COMMENT '时区，如 Asia/Singapore',
    `currency`     VARCHAR(8)            DEFAULT NULL COMMENT '币种，如 SGD',
    `manager_id`   BIGINT                DEFAULT NULL COMMENT '区域管理员用户ID',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-停用',
    `remark`       VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    `version`      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `create_by`    BIGINT                DEFAULT NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`    BIGINT                DEFAULT NULL,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_region_code` (`code`),
    KEY `idx_region_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='海外区域';

-- -----------------------------------------------------------------------------
-- 2. 用户（员工 / 区域管理员 / 总管理员）
-- -----------------------------------------------------------------------------
CREATE TABLE `sys_user`
(
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `username`         VARCHAR(64)  NOT NULL COMMENT '登录账号',
    `password`         VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `real_name`        VARCHAR(64)  NOT NULL COMMENT '姓名',
    `employee_no`      VARCHAR(32)           DEFAULT NULL COMMENT '工号',
    `role`             VARCHAR(32)  NOT NULL COMMENT '角色：SUPER_ADMIN / REGION_ADMIN / EMPLOYEE',
    `region_id`        BIGINT                DEFAULT NULL COMMENT '所属/负责区域（超管为空）',
    `department`       VARCHAR(64)           DEFAULT NULL COMMENT '部门',
    `phone_cipher`     VARCHAR(255)          DEFAULT NULL COMMENT '手机号密文（AES-GCM）',
    `phone_mask`       VARCHAR(32)           DEFAULT NULL COMMENT '手机号掩码，用于列表展示',
    `phone_hash`       VARCHAR(64)           DEFAULT NULL COMMENT '手机号 HMAC，用于精确检索（明文不入库）',
    `email`            VARCHAR(128)          DEFAULT NULL COMMENT '邮箱',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-正常 0-禁用',
    `login_fail_count` INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until`     DATETIME              DEFAULT NULL COMMENT '锁定截止时间',
    `last_login_time`  DATETIME              DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip`    VARCHAR(64)           DEFAULT NULL COMMENT '最后登录IP',
    `version`          INT          NOT NULL DEFAULT 0,
    `create_by`        BIGINT                DEFAULT NULL,
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`        BIGINT                DEFAULT NULL,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`),
    KEY `idx_user_role_region` (`role`, `region_id`),
    KEY `idx_user_phone_hash` (`phone_hash`),
    KEY `idx_user_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户';

-- -----------------------------------------------------------------------------
-- 3. 公寓（宿舍楼）
-- -----------------------------------------------------------------------------
CREATE TABLE `dorm_building`
(
    `id`            BIGINT       NOT NULL,
    `region_id`     BIGINT       NOT NULL COMMENT '所属区域',
    `code`          VARCHAR(32)  NOT NULL COMMENT '公寓编码，如 SG-ONE-NORTH',
    `name`          VARCHAR(128) NOT NULL COMMENT '公寓名称',
    `country`       VARCHAR(64)           DEFAULT NULL,
    `city`          VARCHAR(64)           DEFAULT NULL COMMENT '城市',
    `address`       VARCHAR(255)          DEFAULT NULL COMMENT '详细地址',
    `contact_name`  VARCHAR(64)           DEFAULT NULL COMMENT '公寓联系人',
    `contact_phone` VARCHAR(64)           DEFAULT NULL,
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-停用',
    `remark`        VARCHAR(255)          DEFAULT NULL,
    `version`       INT          NOT NULL DEFAULT 0,
    `create_by`     BIGINT                DEFAULT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`     BIGINT                DEFAULT NULL,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_building_code` (`code`),
    KEY `idx_building_region` (`region_id`, `status`),
    KEY `idx_building_city` (`city`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='公寓（宿舍楼）';

-- -----------------------------------------------------------------------------
-- 4. 房间
-- -----------------------------------------------------------------------------
CREATE TABLE `dorm_room`
(
    `id`              BIGINT        NOT NULL,
    `building_id`     BIGINT        NOT NULL COMMENT '所属公寓',
    `region_id`       BIGINT        NOT NULL COMMENT '冗余：所属区域（便于数据权限过滤）',
    `room_no`         VARCHAR(32)   NOT NULL COMMENT '房间号',
    `floor_no`        INT                    DEFAULT NULL COMMENT '楼层',
    `room_type`       VARCHAR(16)   NOT NULL COMMENT '房型：SINGLE/DOUBLE/TRIPLE/QUAD',
    `capacity`        INT           NOT NULL DEFAULT 1 COMMENT '可住人数（床位数）',
    `gender_limit`    VARCHAR(8)    NOT NULL DEFAULT 'ANY' COMMENT '性别限制：MALE/FEMALE/ANY',
    `status`          VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN-开放 CLOSED-关闭 MAINTENANCE-维修',
    `area_sqm`        DECIMAL(6, 2)          DEFAULT NULL COMMENT '面积（㎡）',
    `price_per_night` DECIMAL(10, 2)         DEFAULT 0.00 COMMENT '每晚价格（当地币种）',
    `currency`        VARCHAR(8)             DEFAULT NULL COMMENT '币种',
    `facilities`      VARCHAR(500)           DEFAULT NULL COMMENT '设施，逗号分隔：WIFI,AC,DESK,BATHROOM',
    `remark`          VARCHAR(255)           DEFAULT NULL,
    `version`         INT           NOT NULL DEFAULT 0,
    `create_by`       BIGINT                 DEFAULT NULL,
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`       BIGINT                 DEFAULT NULL,
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_room_building_no` (`building_id`, `room_no`),
    KEY `idx_room_region_status` (`region_id`, `status`),
    KEY `idx_room_building_status` (`building_id`, `status`),
    KEY `idx_room_type` (`room_type`, `gender_limit`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='房间';

-- -----------------------------------------------------------------------------
-- 5. 床位（多人间的分配粒度）
-- -----------------------------------------------------------------------------
CREATE TABLE `dorm_bed`
(
    `id`            BIGINT       NOT NULL,
    `room_id`       BIGINT       NOT NULL COMMENT '所属房间',
    `building_id`   BIGINT       NOT NULL COMMENT '冗余：所属公寓',
    `region_id`     BIGINT       NOT NULL COMMENT '冗余：所属区域',
    `bed_no`        VARCHAR(16)  NOT NULL COMMENT '床位号，如 A / B / 1 / 2',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'IDLE' COMMENT '状态：IDLE-空闲 OCCUPIED-已占用 DISABLED-停用',
    `current_apply_id` BIGINT             DEFAULT NULL COMMENT '当前占用申请ID（释放时置空）',
    `version`       INT          NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bed_room_no` (`room_id`, `bed_no`),
    KEY `idx_bed_room_status` (`room_id`, `status`),
    KEY `idx_bed_region_status` (`region_id`, `status`),
    KEY `idx_bed_apply` (`current_apply_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='床位';

-- -----------------------------------------------------------------------------
-- 6. 入住申请
-- -----------------------------------------------------------------------------
CREATE TABLE `stay_apply`
(
    `id`                 BIGINT       NOT NULL,
    `apply_no`           VARCHAR(32)  NOT NULL COMMENT '申请单号，如 DA202610140001',
    `employee_id`        BIGINT       NOT NULL COMMENT '申请人',
    `employee_name`      VARCHAR(64)           DEFAULT NULL COMMENT '冗余：申请人姓名',
    `employee_no`        VARCHAR(32)           DEFAULT NULL COMMENT '冗余：工号',
    `region_id`          BIGINT       NOT NULL COMMENT '目的地区域',
    `dest_country`       VARCHAR(64)           DEFAULT NULL COMMENT '目的地国家',
    `dest_city`          VARCHAR(64)           DEFAULT NULL COMMENT '目的地城市',
    `building_id`        BIGINT                DEFAULT NULL COMMENT '期望公寓（可空，由管理员分配）',
    `preferred_room_type` VARCHAR(16)          DEFAULT NULL COMMENT '期望房型',
    `check_in_date`      DATE         NOT NULL COMMENT '入住日期',
    `check_out_date`     DATE         NOT NULL COMMENT '退房日期',
    `nights`             INT          NOT NULL COMMENT '住宿天数',
    `trip_no`            VARCHAR(32)           DEFAULT NULL COMMENT '出差单号',
    `reason`             VARCHAR(500)          DEFAULT NULL COMMENT '出差事由',
    `status`             VARCHAR(16)  NOT NULL COMMENT '状态：PENDING/APPROVED/REJECTED/CHECKED_IN/CHECKED_OUT/CANCELLED/EXPIRED',
    `room_id`            BIGINT                DEFAULT NULL COMMENT '分配房间',
    `bed_id`             BIGINT                DEFAULT NULL COMMENT '分配床位',
    `assign_time`        DATETIME              DEFAULT NULL COMMENT '分配时间',
    `approver_id`        BIGINT                DEFAULT NULL,
    `approver_name`      VARCHAR(64)           DEFAULT NULL,
    `approve_time`       DATETIME              DEFAULT NULL,
    `approve_remark`     VARCHAR(255)          DEFAULT NULL,
    `reject_reason`      VARCHAR(255)          DEFAULT NULL,
    `check_in_time`      DATETIME              DEFAULT NULL COMMENT '实际入住时间',
    `check_out_time`     DATETIME              DEFAULT NULL COMMENT '实际退房时间',
    `cancel_time`        DATETIME              DEFAULT NULL,
    `expire_time`        DATETIME              DEFAULT NULL COMMENT '失效截止（入住日期+1天）',
    `apply_source`       VARCHAR(16)  NOT NULL DEFAULT 'WEB' COMMENT '来源：WEB/AGENT',
    `idempotency_key`    VARCHAR(64)           DEFAULT NULL COMMENT '幂等键（客户端提供）',
    `version`            INT          NOT NULL DEFAULT 0,
    `create_by`          BIGINT                DEFAULT NULL,
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`          BIGINT                DEFAULT NULL,
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_apply_no` (`apply_no`),
    UNIQUE KEY `uk_apply_idempotency` (`idempotency_key`),
    KEY `idx_apply_employee_status` (`employee_id`, `status`),
    KEY `idx_apply_region_status` (`region_id`, `status`),
    KEY `idx_apply_status_time` (`status`, `create_time`),
    KEY `idx_apply_bed` (`bed_id`),
    KEY `idx_apply_dates` (`check_in_date`, `check_out_date`),
    KEY `idx_apply_room` (`room_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='入住申请';

-- -----------------------------------------------------------------------------
-- 7. 入住订单（审批通过并分配房间后生成，贯穿入住-退房生命周期）
-- -----------------------------------------------------------------------------
CREATE TABLE `stay_order`
(
    `id`                BIGINT         NOT NULL,
    `order_no`          VARCHAR(32)    NOT NULL COMMENT '订单号，如 DO202610140001',
    `apply_id`          BIGINT         NOT NULL COMMENT '来源申请',
    `apply_no`          VARCHAR(32)             DEFAULT NULL,
    `employee_id`       BIGINT         NOT NULL,
    `employee_name`     VARCHAR(64)             DEFAULT NULL,
    `region_id`         BIGINT         NOT NULL,
    `building_id`       BIGINT         NOT NULL,
    `room_id`           BIGINT         NOT NULL,
    `bed_id`            BIGINT         NOT NULL,
    `check_in_date`     DATE           NOT NULL,
    `check_out_date`    DATE           NOT NULL,
    `nights`            INT            NOT NULL,
    `real_check_in_time`  DATETIME              DEFAULT NULL,
    `real_check_out_time` DATETIME              DEFAULT NULL,
    `status`            VARCHAR(16)    NOT NULL COMMENT '状态：RESERVED-已预订 CHECKED_IN-已入住 CHECKED_OUT-已退房 CANCELLED-已取消',
    `amount`            DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '房费合计',
    `currency`          VARCHAR(8)              DEFAULT NULL,
    `source`            VARCHAR(16)    NOT NULL DEFAULT 'WEB',
    `early_check_out_reason` VARCHAR(255)        DEFAULT NULL COMMENT '提前退房原因',
    `remark`            VARCHAR(255)            DEFAULT NULL,
    `version`           INT            NOT NULL DEFAULT 0,
    `create_by`         BIGINT                  DEFAULT NULL,
    `create_time`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_by`         BIGINT                  DEFAULT NULL,
    `update_time`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_order_apply` (`apply_id`),
    KEY `idx_order_employee_status` (`employee_id`, `status`),
    KEY `idx_order_region_status` (`region_id`, `status`),
    KEY `idx_order_room` (`room_id`),
    KEY `idx_order_checkout` (`check_out_date`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='入住订单';

-- -----------------------------------------------------------------------------
-- 8. 操作审计日志（对应「可解释、可审计、可回溯」）
-- -----------------------------------------------------------------------------
CREATE TABLE `audit_log`
(
    `id`          BIGINT       NOT NULL,
    `trace_id`    VARCHAR(64)           DEFAULT NULL COMMENT '全链路追踪ID',
    `user_id`     BIGINT                DEFAULT NULL,
    `user_name`   VARCHAR(64)           DEFAULT NULL,
    `role`        VARCHAR(32)           DEFAULT NULL,
    `module`      VARCHAR(32)           DEFAULT NULL COMMENT '模块：AUTH/ROOM/APPLY/ORDER/AGENT/ADMIN',
    `action`      VARCHAR(64)  NOT NULL COMMENT '动作：APPLY_CREATE / APPLY_APPROVE / ROOM_CLOSE ...',
    `target_type` VARCHAR(32)           DEFAULT NULL,
    `target_id`   VARCHAR(64)           DEFAULT NULL,
    `before_json` TEXT                  DEFAULT NULL,
    `after_json`  TEXT                  DEFAULT NULL,
    `result`      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAIL',
    `error_msg`   VARCHAR(500)          DEFAULT NULL,
    `ip`          VARCHAR(64)           DEFAULT NULL,
    `user_agent`  VARCHAR(255)          DEFAULT NULL,
    `cost_ms`     BIGINT                DEFAULT NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_user_time` (`user_id`, `create_time`),
    KEY `idx_audit_trace` (`trace_id`),
    KEY `idx_audit_module_action` (`module`, `action`, `create_time`),
    KEY `idx_audit_target` (`target_type`, `target_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='操作审计日志';

-- -----------------------------------------------------------------------------
-- 9. Outbox 事件表（微服务/异步场景下的最终一致性）
--    审批通过后需要「发通知 / 刷统计 / 同步下游」，与主事务解耦
-- -----------------------------------------------------------------------------
CREATE TABLE `outbox_event`
(
    `id`             BIGINT       NOT NULL,
    `event_type`     VARCHAR(64)  NOT NULL COMMENT '事件类型：APPLY_APPROVED / ORDER_CHECKED_IN ...',
    `aggregate_type` VARCHAR(32)  NOT NULL COMMENT '聚合类型：APPLY / ORDER',
    `aggregate_id`   BIGINT       NOT NULL COMMENT '聚合ID',
    `payload`        TEXT         NOT NULL COMMENT '事件载荷 JSON',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/SENT/FAILED/DEAD',
    `retry_count`    INT          NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME             DEFAULT NULL,
    `last_error`     VARCHAR(500)          DEFAULT NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_outbox_status_retry` (`status`, `next_retry_time`),
    KEY `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='领域事件发件箱';

