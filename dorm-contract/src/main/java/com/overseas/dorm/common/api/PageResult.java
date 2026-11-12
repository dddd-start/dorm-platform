package com.overseas.dorm.common.api;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 统一分页结果（纯数据结构，不依赖任何持久层框架）。
 *
 * <p>与 MyBatis-Plus {@code IPage} 的转换放在 domain 层的
 * {@code com.overseas.dorm.domain.support.PageMapper}，
 * 以保证 common 层不反向依赖持久层。
 */
@Data
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据。 */
    private List<T> records;

    /** 总条数。 */
    private long total;

    /** 当前页码，从 1 开始。 */
    private long current;

    /** 每页条数。 */
    private long size;

    /** 总页数。 */
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        PageResult<T> r = new PageResult<>();
        r.records = records == null ? Collections.emptyList() : records;
        r.total = total;
        r.current = current;
        r.size = size;
        r.pages = size <= 0 ? 0 : (total + size - 1) / size;
        return r;
    }

    public static <T> PageResult<T> empty() {
        return of(Collections.emptyList(), 0, 1, 0);
    }

    /** 记录级别的视图转换（实体 -> VO），保持分页元信息不变。 */
    public <R> PageResult<R> map(java.util.function.Function<T, R> mapper) {
        return PageResult.of(records == null ? Collections.emptyList() : records.stream().map(mapper).toList(),
                total, current, size);
    }
}
