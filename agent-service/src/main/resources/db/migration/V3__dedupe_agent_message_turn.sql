-- ============================================================================
-- H1 修复：并发提交同一个 turnId 会产生多条助手消息（并重复调用模型、重复计费）
--
-- 背景（独立验证实测）：同一 conversationId + turnId 并发提交 4 次 →
-- 生成 3 条助手消息、调用模型 3 次、agent_usage_daily.turn_count 被多计。
-- 根因：turn_id 上只有**非唯一**索引 idx_msg_turn，
-- 而应用层的"先查后插"（selectAssistantByTurn → beginTurn）不是原子操作 ——
-- 并发下多个请求都能查到"不存在"，于是都去插入并调用模型。
--
-- 修复思路：把这条不变量交给数据库来保证（唯一索引），应用层只负责在
-- 冲突时读出既有消息并重放。这比在应用层加锁更可靠：
-- 它同时覆盖多实例部署（应用层锁在跨实例时无效）。
-- ============================================================================

-- 1) 先清理历史上已经产生的重复行：同一 (conversation_id, turn_id, role) 只保留 id 最小的一条
--    （保留最早的那条，因为它是真正被返回给用户、也真正计费过的那一条）
DELETE m1 FROM agent_message m1
  JOIN agent_message m2
    ON m1.conversation_id = m2.conversation_id
   AND m1.turn_id = m2.turn_id
   AND m1.role = m2.role
   AND m1.id > m2.id
 WHERE m1.turn_id IS NOT NULL;

-- 2) 加上唯一索引。注意必须包含 role：
--    正常一轮对话里，用户消息与助手消息**共用同一个 turnId**（幂等查找按
--    turnId + role=ASSISTANT 定位，加后缀会让幂等静默失效），
--    因此唯一键是三列而不是两列。
--    turn_id 为 NULL 的历史行不影响：MySQL 唯一索引允许多个 NULL。
ALTER TABLE agent_message
  ADD UNIQUE KEY uk_msg_turn_role (conversation_id, turn_id, role);
