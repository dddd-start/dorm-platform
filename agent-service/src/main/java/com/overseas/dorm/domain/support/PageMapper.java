package com.overseas.dorm.domain.support;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.overseas.dorm.common.api.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * 分页转换助手：把 MyBatis-Plus 的 {@code IPage} 转成与持久层解耦的 {@link PageResult}。
 *
 * <p>放在 domain 层而不是 common 层，是为了让 common 不反向依赖 MyBatis-Plus ——
 * 通用层一旦依赖持久层，就无法在 Agent / 前端 BFF 等场景中独立复用。
 */
public final class PageMapper {

    /** 单页最大条数，与分页插件的 maxLimit 保持一致。 */
    public static final long MAX_PAGE_SIZE = 200L;

    private PageMapper() {
    }

    /** 归一化分页参数：页码从 1 开始，页大小受上限约束。 */
    public static <T> Page<T> page(long current, long size) {
        long c = Math.max(current, 1);
        long s = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(c, s);
    }

    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        List<T> records = page.getRecords().stream().map(mapper).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public static <T> PageResult<T> of(IPage<T> page) {
        return of(page, Function.identity());
    }
}
