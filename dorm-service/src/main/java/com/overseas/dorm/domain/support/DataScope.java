package com.overseas.dorm.domain.support;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.springframework.stereotype.Component;

/**
 * 数据权限（行级权限）校验。
 *
 * <p>这是「防止越权」中<b>数据维度</b>的那一半（另一半是接口级的功能权限）。
 * 三条规则：
 * <ol>
 *   <li><b>总管理员</b>：不限制区域；</li>
 *   <li><b>区域管理员</b>：只能操作/查看本区域数据；</li>
 *   <li><b>员工</b>：只能操作/查看「自己的」数据；
 *       查房源这类公共数据则按区域开放（员工可以出差去任何区域，需要先看得到房源）。</li>
 * </ol>
 *
 * <p>为什么做成独立组件而不是散在各 Service：越权漏洞几乎都源于
 * 「某个查询忘了加区域条件」。集中到一个类里，评审和测试都只需盯这一处，
 * 而且 Agent 工具也能复用同一套规则 —— 模型永远拿不到越权的数据。
 */
@Component
public class DataScope {

    /** 当前登录用户；未登录直接 401。 */
    public LoginUser currentUser() {
        return UserContext.require();
    }

    /** 当前用户可见的区域 ID；返回 null 表示「全部区域」（总管理员）。 */
    public Long visibleRegionId() {
        LoginUser user = currentUser();
        return user.isSuperAdmin() ? null : user.getRegionId();
    }

    /** 校验用户是否有权访问指定区域的数据。 */
    public void assertRegionAccess(Long regionId) {
        LoginUser user = currentUser();
        if (user.isSuperAdmin()) {
            return;
        }
        if (regionId == null) {
            throw BizException.of(ResultCode.DATA_SCOPE_DENIED, "该数据未归属任何区域，当前角色无权访问");
        }
        if (!regionId.equals(user.getRegionId())) {
            throw BizException.of(ResultCode.DATA_SCOPE_DENIED,
                    "你负责的区域是「%s」，无权访问其它区域的数据".formatted(
                            user.getRegionId() == null ? "未分配" : String.valueOf(user.getRegionId())));
        }
    }

    /**
     * 校验员工只能访问自己的数据。
     *
     * @param ownerUserId 数据归属人
     */
    public void assertSelfOrAdmin(Long ownerUserId) {
        LoginUser user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (ownerUserId == null || !ownerUserId.equals(user.getUserId())) {
            throw BizException.of(ResultCode.DATA_SCOPE_DENIED, "你只能查看自己的数据");
        }
    }

    /**
     * 汇总/统计类查询的**行级范围**（统计粒度的唯一收口点）。
     *
     * <h3>为什么必须收口在一处</h3>
     * H7 的缺口是这么来的：分页列表接口逐个补了区域条件，统计接口却是"另写一遍"，
     * 于是同一份数据在列表里越权拦得住、在统计里拦不住（实测普通员工看到全库
     * {@code TOTAL=335/50}，与总管理员完全一致，而分页接口的 {@code total=38} 是对的）。
     * 逐接口打补丁必然继续漏，所以粒度规则集中到这里，由所有统计入口调用。
     *
     * <h3>三种角色的粒度</h3>
     * <ul>
     *   <li><b>总管理员</b>：不限制（可选择性只看某个区域）；</li>
     *   <li><b>区域管理员</b>：只看本区域。未分配区域时<b>拒绝</b>而不是"不过滤" ——
     *       原来的写法是 {@code regionId == null ⇒ 不加条件}，一个没分配区域的
     *       管理账号会静默看到全库数字（fail-open）；</li>
     *   <li><b>员工</b>：只看<b>自己</b>的数据（{@code employee_id = me}）。
     *       注意这里与"查房源"不同：房源是公共资源、按区域开放，
     *       而"全公司有多少申请/订单"属于他人数据，员工无权看聚合值。</li>
     * </ul>
     *
     * <h3>为什么不能只靠 visibleRegionId()</h3>
     * {@code visibleRegionId()} 对员工返回的是"他的区域"，用来过滤业务量是错的：
     * 那会让员工看到本区域所有人的申请数量（H7 实测就是这个结果）。
     */
    public StatsScope statisticsScope(Long requestedRegionId) {
        LoginUser user = currentUser();
        if (user.isSuperAdmin()) {
            return new StatsScope(requestedRegionId, null);
        }
        if (user.isEmployee()) {
            return new StatsScope(null, user.getUserId());
        }
        if (user.getRegionId() == null) {
            throw BizException.of(ResultCode.DATA_SCOPE_DENIED,
                    "当前账号未分配区域，无法查看汇总数据（请联系总管理员分配区域）");
        }
        return new StatsScope(user.getRegionId(), null);
    }

    /**
     * 统计查询的过滤范围。
     *
     * @param regionId   按区域收窄；null 表示不限区域
     * @param employeeId 按员工收窄；null 表示不限员工（员工角色下必然非空）
     */
    public record StatsScope(Long regionId, Long employeeId) {
    }

    /** 校验必须是管理员（用于审批、分房、房源维护）。 */
    public void assertAdmin() {
        if (!currentUser().isAdmin()) {
            throw BizException.of(ResultCode.FORBIDDEN, "该操作仅管理员可执行");
        }
    }

    public void assertSuperAdmin() {
        if (!currentUser().isSuperAdmin()) {
            throw BizException.of(ResultCode.FORBIDDEN, "该操作仅总管理员可执行");
        }
    }

    /**
     * 给查询条件自动追加区域过滤。
     *
     * <p>用法：{@code scope.applyRegion(wrapper, DormRoom::getRegionId)}
     * 这样「列表查询忘记加区域条件」这种经典漏洞在编码阶段就被消灭了。
     */
    public <T> LambdaQueryWrapper<T> applyRegion(LambdaQueryWrapper<T> wrapper, SFunction<T, Long> regionColumn) {
        Long regionId = visibleRegionId();
        if (regionId != null) {
            wrapper.eq(regionColumn, regionId);
        }
        return wrapper;
    }

}
