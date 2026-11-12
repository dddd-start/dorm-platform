-- ============================================================================
-- 历史消息保留 RAG 引用来源
--
-- 真实 API 联调发现：SSE 的 REFERENCE 帧能显示来源，但刷新页面后
-- /conversations/{id}/messages 没有 references 字段，前端只能渲染空列表。
-- 回答正文里写着“根据《宿舍费用与报销标准》”，用户却点不开来源，
-- 可解释性在刷新后断掉。
--
-- 把引用快照随助手消息落库；归档表同步加列，保证冷数据也保留来源。
-- ============================================================================

ALTER TABLE agent_message
    ADD COLUMN references_json TEXT DEFAULT NULL COMMENT '本轮 RAG 引用快照（title+snippet）'
        AFTER tool_calls_json;

ALTER TABLE agent_message_archive
    ADD COLUMN references_json TEXT DEFAULT NULL COMMENT '本轮 RAG 引用快照（title+snippet）'
        AFTER tool_calls_json;
