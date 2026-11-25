package com.overseas.dorm.domain.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 批量操作结果。
 *
 * <p>批量接口（批量开关房、批量启停床位）必须逐条返回成功与失败原因，
 */
public record BatchResultVO(
        int successCount,
        int failCount,
        List<BatchFailure> failures
) implements Serializable {

    /**
     * 单条失败原因。
     *
     * <p>{@code roomId} 与 {@code bedId} 二选一（房间批量操作填 roomId，
     * 床位批量操作填 bedId）；序列化时 null 字段会被 Jackson 省略，
     * 因此前端不会收到一堆无意义的 null。
     */
    public record BatchFailure(
            Long roomId,
            Long bedId,
            String roomNo,
            String reason
    ) implements Serializable {
    }

    /** 房间维度的失败项。 */
    public static BatchFailure roomFailure(Long roomId, String roomNo, String reason) {
        return new BatchFailure(roomId, null, roomNo, reason);
    }

    /** 床位维度的失败项。 */
    public static BatchFailure bedFailure(Long bedId, String reason) {
        return new BatchFailure(null, bedId, null, reason);
    }
}
