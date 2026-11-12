package com.overseas.dorm.common.constant;

/**
 * 业务与实现上的数值约束。
 *
 * <p>为什么集中到一处：散落在各处的数字字面量有两个具体危害 ——
 * <ol>
 *   <li><b>看不出含义</b>。{@code if (nights > 90)} 里的 90 是"最长住宿晚数"，
 *       但读者只能猜；{@code 6} 更糟，可能是"6 个月"也可能是"6 条"；</li>
 *   <li><b>改不干净</b>。同一条规则在多个文件里各写一遍，
 *       改的时候必然漏掉一处，于是出现"创建申请允许 90 晚、续住校验按 30 晚"这种不一致。</li>
 * </ol>
 *
 * <p>分类原则：
 * <ul>
 *   <li>真正需要<b>运行时调参</b>的（模型、限流、线程池、TTL）放在
 *       {@code AgentProperties} / {@code application.yml} 里；</li>
 *   <li><b>由业务规则或技术约束决定、不需要现场调</b>的放这里，用常量表达意图。</li>
 * </ul>
 */
public final class BusinessLimits {

    private BusinessLimits() {
    }

    // ================================================================ 入住业务

    /** 单次入住最长晚数。超过需要走特批流程，因此这里硬性拒绝。 */
    public static final int MAX_STAY_NIGHTS = 90;

    /** 最多可提前多少个月提交入住申请（再远的行程变数太大，提前分配会占用床位）。 */
    public static final int MAX_ADVANCE_MONTHS = 6;

    /** 一个房间最多支持多少张床位。床位号用 A-Z 表示，因此上限是 26。 */
    public static final int MAX_BEDS_PER_ROOM = 26;

    // ================================================================ 查询与分页

    /** 分页查询的默认每页条数（前端未指定时使用）。 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 分页查询的硬上限，与 MyBatis-Plus 分页插件的 maxLimit 保持一致。 */
    public static final int MAX_PAGE_SIZE = 200;

    /** 下拉选项类接口（区域/公寓/用户）最多返回多少条。 */
    public static final int MAX_OPTIONS_RETURNED = 50;

    /** 待审批/逾期等"清单类"接口的默认返回条数。 */
    public static final int DEFAULT_LIST_LIMIT = 10;

    /** 清单类接口的硬上限，防止调用方传 limit=100000 把库拖垮。 */
    public static final int MAX_LIST_LIMIT = 20;

    /** 会话消息历史的默认与最大返回条数。 */
    public static final int DEFAULT_MESSAGE_LIMIT = 100;
    public static final int MAX_MESSAGE_LIMIT = 500;

    /** 会话列表默认每页条数。 */
    public static final int CONVERSATION_PAGE_SIZE = 20;

    // ================================================================ 字段与日志截断

    /** 审计日志里 before/after 快照的最大长度，避免超大对象把审计表撑爆。 */
    public static final int AUDIT_JSON_MAX_LENGTH = 4000;

    /** 工具调用参数的最大记录长度。 */
    public static final int TOOL_ARGS_MAX_LENGTH = 4000;

    /** 工具调用结果的最大记录长度（工具结果可能很长，超长部分截断并标注）。 */
    public static final int TOOL_RESULT_MAX_LENGTH = 8000;

    /** 错误信息入库前的最大长度。 */
    public static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    /** 检索日志中问题文本的最大长度（与表字段 VARCHAR(500) 对齐并留出余量）。 */
    public static final int QUERY_TEXT_MAX_LENGTH = 480;

    /** 会话标题的最大长度。 */
    public static final int TITLE_MAX_LENGTH = 60;

    /** 会话列表里标题的展示长度（比入库上限短，避免列表被长标题撑开）。 */
    public static final int TITLE_DISPLAY_LENGTH = 40;

    // ================================================================ 技术实现约束

    /** 会话内消息序号分配的最大重试次数（并发冲突时重试）。 */
    public static final int SEQ_ALLOCATE_RETRY = 5;

    /** 定时任务单批扫描条数（如"过期未入住申请释放床位"）。分批是为了避免一次扫全表。 */
    public static final int SCHEDULED_SCAN_BATCH = 200;

    /** 读取会话历史时一次最多取多少条（作为记忆窗口的物理上限）。 */
    public static final int MEMORY_READ_LIMIT = 200;

    /** 断点续传时一次最多补发多少条历史事件（防止一次拉取过多事件占满内存）。 */
    public static final int RESUME_REPLAY_LIMIT = 1000;

    /** 幂等重放时把回答切分的段落长度（既保留"流式感"又不过度占用连接）。 */
    public static final int REPLAY_CHUNK_SIZE = 40;

    /** 限流令牌桶键的存活时间（秒）：只需覆盖一个限流窗口即可。 */
    public static final long RATE_LIMIT_KEY_TTL_SECONDS = 3600L;

    /** 静态资源（前端构建产物）的浏览器缓存时长（秒）。 */
    public static final long STATIC_RESOURCE_CACHE_SECONDS = 3600L;

    /** CORS 预检结果的缓存时长（秒）。 */
    public static final long CORS_MAX_AGE_SECONDS = 3600L;

    /** HTTP 请求里 {@code "Bearer "} 前缀的长度（用于截取 token）。 */
    public static final int BEARER_PREFIX_LENGTH = 7;

    /** 估算 token 数时，非中日韩字符按多少字符折算一个 token。 */
    public static final int CHARS_PER_TOKEN_FOR_NON_CJK = 4;

    /** 中日韩统一表意文字在 Unicode 中的起始/结束码点（用于估算 token 与统计字数）。 */
    public static final char CJK_START = '\u4E00';
    public static final char CJK_END = '\u9FFF';
}
