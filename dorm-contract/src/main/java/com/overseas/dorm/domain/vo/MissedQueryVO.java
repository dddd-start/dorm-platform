package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * "一次都没检索到"的问题排行（SQL 聚合结果）。
 *
 * <p>这张榜单是知识库建设最直接的输入：它直接告诉你员工在问什么、
 * 而系统答不上来。
 */
@lombok.Data
public class MissedQueryVO implements Serializable {

    /** 原始问题文本。 */
    private String queryText;

    /** 出现次数。 */
    private Long cnt;
}
