package com.overseas.dorm.common.constant;

/**
 * Redis Key 规范。
 *
 * <p>所有键集中定义，避免各模块自造前缀导致「缓存清不掉、限流数不准、
 * 流缓冲残留」等线上问题。命名约定：
 * <pre>
 *   dorm:{业务域}:{对象}[:{维度}]
 * </pre>
 * 统一前缀 {@code dorm:} 让运维可以用 {@code SCAN dorm:*} 做安全清理，
 * 也便于未来按业务域拆分到不同 Redis 实例。
 */
public final class RedisKeys {

    public static final String PREFIX = "dorm:";

    private RedisKeys() {
    }

    // ------------------------------------------------------------ 认证与限流
    /** 登录失败计数：dorm:auth:fail:{username} */
    public static String loginFail(String username) {
        return PREFIX + "auth:fail:" + username;
    }

    /** 登录锁定：dorm:auth:lock:{username} */
    public static String loginLock(String username) {
        return PREFIX + "auth:lock:" + username;
    }

    /** 强制下线/会话白名单：dorm:auth:session:{userId}:{sessionId} */
    public static String session(Long userId, String sessionId) {
        return PREFIX + "auth:session:" + userId + ":" + sessionId;
    }

    /**
     * 认证版本（epoch）：{@code dorm:auth:epoch:{userId}}，任何"权限/凭据变更"都递增它，
     * 令牌里带的 epoch 一旦落后即视为失效。
     *
     * <p>它解决的是"变更传播"这一类问题（同族缺陷一次覆盖）：
     * 禁用账号、重置口令、角色/区域变更、强制下线全部设备，这些动作都必须在
     * <b>已签发令牌的有效期内立即生效</b>，而纯无状态 JWT 做不到。
     * 会话白名单只能按 (userId, sessionId) 精确删某一个会话，无法表达"这个用户的所有旧令牌作废"。
     *
     * <p>键不存在时按 0 处理（绝大多数用户从未被吊销过，因此登录时无需写入）。
     */
    public static String authEpoch(Long userId) {
        return PREFIX + "auth:epoch:" + userId;
    }

    /** 对话轮次令牌桶：dorm:ratelimit:agent:turn:{userId} */
    public static String agentTurnBucket(Long userId) {
        return PREFIX + "ratelimit:agent:turn:" + userId;
    }


    // ------------------------------------------------------------ Agent 流式
    /** 流式 token 缓冲区（Redis Stream）：dorm:stream:{messageId} */
    public static String streamBuffer(Long messageId) {
        return PREFIX + "stream:" + messageId;
    }

    /** 流式实时广播频道（Redis Pub/Sub）：dorm:stream:live:{messageId} */
    public static String streamLiveChannel(Long messageId) {
        return PREFIX + "stream:live:" + messageId;
    }

    /** 流式状态机：GENERATING / DONE / INTERRUPTED / BLOCKED / FAILED */
    public static String streamState(Long messageId) {
        return PREFIX + "stream:state:" + messageId;
    }

    /** 用户当前并发流计数：dorm:stream:user:{userId} */
    public static String userStreamCount(Long userId) {
        return PREFIX + "stream:user:" + userId;
    }

    /** 全局并发流计数：dorm:stream:global */
    public static String globalStreamCount() {
        return PREFIX + "stream:global";
    }


    /** 生成中的任务取消标记：dorm:stream:cancel:{messageId} */
    public static String streamCancel(Long messageId) {
        return PREFIX + "stream:cancel:" + messageId;
    }

    // ------------------------------------------------------------ 业务并发控制
    /** 床位分配分布式锁：dorm:lock:assign:{bedId} */
    public static String assignLock(Long bedId) {
        return PREFIX + "lock:assign:" + bedId;
    }


    /** 业务单号日序列：dorm:seq:{type}:{yyyyMMdd} */
    public static String bizSeq(String type, String date) {
        return PREFIX + "seq:" + type + ":" + date;
    }

    // ------------------------------------------------------------ 缓存
    /** 嵌入向量缓存：dorm:cache:embed:{model}:{sha256} */
    public static String embeddingCache(String model, String hash) {
        return PREFIX + "cache:embed:" + model + ":" + hash;
    }

    /** 检索结果缓存：dorm:cache:retrieval:{hash} */
    public static String retrievalCache(String hash) {
        return PREFIX + "cache:retrieval:" + hash;
    }




    /** 工具调用去重守卫：dorm:toolguard:{conversationId}:{turnId} */
    public static String toolGuard(Long conversationId, String turnId) {
        return PREFIX + "toolguard:" + conversationId + ":" + turnId;
    }

}
