package com.overseas.dorm.domain.support;

import com.overseas.dorm.domain.vo.StatusCountVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态分布汇总。
 *
 * <p>申请与订单的"各状态数量"需求完全一致，此前在两个 Service 里各写了一遍
 * （包括那个 {@code "TOTAL"} 键名）。重复实现的代价不是多敲几行字，
 * 而是<b>同一件事会有两个版本</b>：某天有人给申请加了"未统计状态归入 OTHER"，
 * 订单那份就悄悄不一致了，而两个接口各自的返回值看起来都很正常。
 *
 * <p>返回类型是 {@code Map<String, Long>} 而不是 {@code Map<String, Object>}：
 * 状态名是<b>动态键</b>（由数据决定），这里用 Map 是合适的；
 * 但值一定是数量，声明成 Long 能让调用方少一次强转，也能让 Swagger 说清楚类型。
 */
public final class StatusSummaryBuilder {

    /** 总计项的键名。前端与 Agent 工具都依赖它，因此集中定义避免多处拼写不一致。 */
    public static final String TOTAL_KEY = "TOTAL";

    private StatusSummaryBuilder() {
    }

    /**
     * 把状态计数行汇总成"状态名 → 数量"，并追加一项总计。
     *
     * <p>用 {@link LinkedHashMap} 保持数据库返回的顺序，让前端把状态按业务顺序展示，
     * 而不是每次刷新顺序都可能变（HashMap 的顺序不保证稳定）。
     */
    public static Map<String, Long> build(List<StatusCountVO> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        long total = 0L;
        if (rows != null) {
            for (StatusCountVO row : rows) {
                if (row == null || row.getStatus() == null) {
                    continue;
                }
                long count = row.getCnt() == null ? 0L : row.getCnt();
                result.put(row.getStatus(), count);
                total += count;
            }
        }
        result.put(TOTAL_KEY, total);
        return result;
    }
}
