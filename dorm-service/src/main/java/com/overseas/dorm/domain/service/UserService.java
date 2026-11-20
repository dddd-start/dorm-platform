package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.domain.dto.UserSaveRequest;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.SysUser;
import com.overseas.dorm.domain.mapper.RegionMapper;
import com.overseas.dorm.domain.mapper.SysUserMapper;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.support.PageMapper;
import com.overseas.dorm.domain.support.PhoneCipher;
import com.overseas.dorm.domain.vo.UserOptionVO;
import com.overseas.dorm.domain.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理服务（仅总管理员可写）。
 *
 * <p>关于「删除用户」：本系统不提供物理删除，只有 {@code status=0} 禁用。
 * 原因是申请单、订单、审计日志都引用 userId ——
 * 把用户删掉会让历史单据变成孤儿数据，审计链直接断掉。
 * 这也是几乎所有企业系统的共同选择。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final RegionMapper regionMapper;
    private final PhoneCipher phoneCipher;
    private final DataScope dataScope;
    private final AuditService auditService;
    private final AuthService authService;
    private final StringRedisTemplate redis;

    public PageResult<UserVO> page(long current, long size, String keyword, UserRole role,
                                   Long regionId, Integer status) {
        dataScope.assertSuperAdmin();
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getEmployeeNo, keyword));
        }
        wrapper.eq(role != null, SysUser::getRole, role)
                .eq(regionId != null, SysUser::getRegionId, regionId)
                .eq(status != null, SysUser::getStatus, status)
                .orderByAsc(SysUser::getId);
        Map<Long, String> regionNames = regionNameMap();
        return PageMapper.of(userMapper.selectPage(PageMapper.page(current, size), wrapper),
                u -> UserVO.of(u, regionNames.get(u.getRegionId())));
    }

    public UserVO detail(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        // 员工只能看自己；管理员可看他人（但不返回明文手机号）
        dataScope.assertSelfOrAdmin(id);
        // 上面只区分「员工 / 管理员」，对区域管理员等于全放行：sea.admin 能读欧洲区管理员 9003。
        // 用户也是带区域的数据，必须再走一次区域校验（总管理员不受限）。
        dataScope.assertRegionAccess(user.getRegionId());
        Region region = user.getRegionId() == null ? null : regionMapper.selectById(user.getRegionId());
        return UserVO.of(user, region == null ? null : region.getName());
    }

    /** 新增或编辑用户。 */
    @Transactional
    public UserVO save(UserSaveRequest request) {
        dataScope.assertSuperAdmin();
        if (request.role() == UserRole.REGION_ADMIN && request.regionId() == null) {
            throw BizException.param("区域管理员必须指定负责区域");
        }
        if (request.regionId() != null && regionMapper.selectById(request.regionId()) == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }

        if (request.id() == null) {
            return create(request);
        }
        return update(request);
    }

    private UserVO create(UserSaveRequest request) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.username()));
        if (exists != null && exists > 0) {
            throw BizException.of(ResultCode.DUPLICATE_DATA, "账号已存在：" + request.username());
        }
        if (request.password() == null || request.password().isBlank()) {
            throw BizException.param("新增用户必须设置初始密码");
        }
        authService.validatePasswordStrength(request.password());

        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(authService.encodePassword(request.password()));
        user.setRealName(request.realName());
        user.setEmployeeNo(request.employeeNo());
        user.setRole(request.role());
        user.setRegionId(request.regionId());
        user.setDepartment(request.department());
        user.setEmail(request.email());
        user.setStatus(request.status() == null ? 1 : request.status());
        user.setLoginFailCount(0);
        if (request.phone() != null && !request.phone().isBlank()) {
            PhoneCipher.CipherBundle bundle = phoneCipher.encrypt(request.phone());
            user.setPhoneCipher(bundle.cipher());
            user.setPhoneMask(bundle.mask());
            user.setPhoneHash(bundle.hash());
        }
        userMapper.insert(user);
        auditService.success(AuditEnums.Module.USER, "USER_CREATE", "SYS_USER", user.getId(),
                null, maskForAudit(user));
        log.info("新增用户 username={} role={}", user.getUsername(), user.getRole());
        return detail(user.getId());
    }

    private UserVO update(UserSaveRequest request) {
        SysUser exists = userMapper.selectById(request.id());
        if (exists == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(request.id());
        update.setRealName(request.realName());
        update.setEmployeeNo(request.employeeNo());
        update.setRole(request.role());
        update.setRegionId(request.regionId());
        update.setDepartment(request.department());
        update.setEmail(request.email());
        update.setStatus(request.status());
        if (request.password() != null && !request.password().isBlank()) {
            authService.validatePasswordStrength(request.password());
            update.setPassword(authService.encodePassword(request.password()));
        }
        if (request.phone() != null && !request.phone().isBlank()) {
            PhoneCipher.CipherBundle bundle = phoneCipher.encrypt(request.phone());
            update.setPhoneCipher(bundle.cipher());
            update.setPhoneMask(bundle.mask());
            update.setPhoneHash(bundle.hash());
        }
        userMapper.updateById(update);
        auditService.success(AuditEnums.Module.USER, "USER_UPDATE", "SYS_USER", request.id(),
                maskForAudit(exists), maskForAudit(userMapper.selectById(request.id())));
        // 角色/区域变更会让旧 token 里的声明过期。原实现只打了一行日志，注释却写"强制该用户重新登录"——
        // 实际旧 token 里的 role/regionId 声明照旧可用满 12h，属于降权不生效。
        // 现在真正吊销：三条路径（改密、降权/换区域、禁用）都递增认证版本。
        if (exists.getRole() != request.role()
                || !java.util.Objects.equals(exists.getRegionId(), request.regionId())
                || (request.password() != null && !request.password().isBlank())) {
            authService.revokeAllSessions(request.id());
            log.info("用户角色/区域/口令变更，已吊销其全部会话 userId={}", request.id());
        }
        return detail(request.id());
    }

    /**
     * 启用/禁用账号。
     *
     * <p><b>【H11 修复】禁用立即生效</b>：原实现只删了登录失败计数器
     * （{@code redis.delete(RedisKeys.loginFail(...))}），**从未碰过会话白名单** ——
     * 而 javadoc 却写着"禁用时同步清理 Redis 中的会话，实现「立即失效」"。
     * 独立验收实测：禁用后同一个 token 仍可 {@code /api/auth/me}（code=0）、
     * 读申请列表（code=0）、**甚至能建单成功**（写操作），直到 token 自然过期（最长 12h）。
     * 而登录路径是校验 status 的（2006「账号已被禁用」）—— 两条路径的口径不一致。
     *
     * <p>现在改为递增该用户的<b>认证版本</b>：禁用后所有已签发令牌立即失效，
     * 无需枚举会话、无竞态、O(1)。
     */
    @Transactional
    public void toggleStatus(Long id, Integer status) {
        dataScope.assertSuperAdmin();
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.param("状态只能是 0 或 1");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (id.equals(dataScope.currentUser().getUserId()) && status == 0) {
            throw BizException.param("不能禁用自己的账号");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        userMapper.updateById(update);
        if (status == 0) {
            // 吊销全部已签发令牌（这就是 javadoc 一直承诺、但代码从未实现的"立即失效"）
            authService.revokeAllSessions(id);
            redis.delete(RedisKeys.loginFail(user.getUsername()));
            log.info("账号已禁用并吊销其全部会话 userId={} username={}", id, user.getUsername());
        }
        auditService.success(AuditEnums.Module.USER, status == 1 ? "USER_ENABLE" : "USER_DISABLE",
                "SYS_USER", id, Map.of("status", user.getStatus()), Map.of("status", status));
    }

    /** 重置密码（总管理员操作，返回新密码由管理员转告）。 */
    @Transactional
    public void resetPassword(Long id, String newPassword) {
        dataScope.assertSuperAdmin();
        authService.validatePasswordStrength(newPassword);
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(authService.encodePassword(newPassword));
        update.setLoginFailCount(0);
        update.setLockedUntil(null);
        userMapper.updateById(update);
        auditService.success(AuditEnums.Module.USER, "USER_RESET_PASSWORD", "SYS_USER", id, null,
                Map.of("resetBy", dataScope.currentUser().getUserId()));
    }

    /** 选人下拉：只返回必要字段，且不返回手机号（数据最小化）。 */
    public List<UserOptionVO> options(String keyword, UserRole role) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .eq(role != null, SysUser::getRole, role);
        // 下拉同样泄露人员名册：区域管理员只应看到本区人员。
        // 原实现无任何区域条件，sea.admin 拉一次就能拿到全公司名单。
        Long scopeRegion = dataScope.visibleRegionId();
        wrapper.eq(scopeRegion != null, SysUser::getRegionId, scopeRegion);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getEmployeeNo, keyword));
        }
        wrapper.orderByAsc(SysUser::getId).last("LIMIT " + BusinessLimits.MAX_OPTIONS_RETURNED);
        return userMapper.selectList(wrapper).stream()
                .map(u -> new UserOptionVO(
                        u.getId(),
                        u.getUsername(),
                        u.getRealName(),
                        u.getEmployeeNo() == null ? "" : u.getEmployeeNo(),
                        u.getRole().name(),
                        u.getRole().getLabel()))
                .toList();
    }

    public Map<Long, String> regionNameMap() {
        return regionMapper.selectList(new LambdaQueryWrapper<Region>()).stream()
                .collect(Collectors.toMap(Region::getId, Region::getName, (a, b) -> a));
    }

    /** 审计用的安全视图：绝不让手机号密文/哈希进入审计快照。 */
    private Map<String, Object> maskForAudit(SysUser user) {
        if (user == null) {
            return null;
        }
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "realName", String.valueOf(user.getRealName()),
                "role", String.valueOf(user.getRole()),
                "regionId", String.valueOf(user.getRegionId()),
                "status", String.valueOf(user.getStatus()));
    }
}
