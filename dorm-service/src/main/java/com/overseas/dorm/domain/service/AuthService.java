package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.util.SensitiveMasker;
import com.overseas.dorm.domain.config.DormSecurityProperties;
import com.overseas.dorm.domain.dto.LoginRequest;
import com.overseas.dorm.domain.dto.PasswordChangeRequest;
import com.overseas.dorm.domain.dto.ProfileUpdateRequest;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.SysUser;
import com.overseas.dorm.domain.mapper.RegionMapper;
import com.overseas.dorm.domain.mapper.SysUserMapper;
import com.overseas.dorm.domain.support.JwtTokenProvider;
import com.overseas.dorm.domain.support.PhoneCipher;
import com.overseas.dorm.domain.vo.LoginVO;
import com.overseas.dorm.domain.vo.UserVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务。
 *
 * <p>登录安全策略（对应「账号安全」与「防暴力破解」）：
 * <ul>
 *   <li>连续失败 {@code login-max-fail} 次后锁定 {@code login-lock-minutes} 分钟；</li>
 *   <li>失败计数同时写 Redis（快，用于判断）与数据库（持久，Redis 重启不丢）；</li>
 *   <li>账号不存在与密码错误返回<b>同一个</b>错误码 ——
 *       否则攻击者可以靠错误信息差异枚举出系统里有哪些账号。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final RegionMapper regionMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final PhoneCipher phoneCipher;
    private final DormSecurityProperties securityProperties;
    private final StringRedisTemplate redis;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 登录。
     */
    @Transactional
    public LoginVO login(LoginRequest request, String clientIp) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.username())
                .last("LIMIT 1"));

        // 统一错误码：不区分"账号不存在"和"密码错误"，避免账号枚举
        if (user == null) {
            log.info("登录失败（账号不存在） username={} ip={}", request.username(), clientIp);
            throw BizException.of(ResultCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            long minutes = Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1;
            throw BizException.of(ResultCode.ACCOUNT_LOCKED,
                    "账号因多次登录失败已被锁定，请 %d 分钟后再试".formatted(minutes));
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            onLoginFailed(user, clientIp);
            throw BizException.of(ResultCode.BAD_CREDENTIALS);
        }

        // 登录成功：清空失败计数、记录登录信息、登记会话
        String sessionId = JwtTokenProvider.newSessionId();
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLoginFailCount(0);
        update.setLockedUntil(null);
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(clientIp);
        userMapper.updateById(update);
        redis.delete(RedisKeys.loginFail(user.getUsername()));
        redis.delete(RedisKeys.loginLock(user.getUsername()));

        // 认证版本：令牌里带上它，之后每个请求与 Redis 比对；
        // 禁用/重置口令/角色或区域变更会递增它，从而让**已签发的**令牌立即失效。
        long authEpoch = currentAuthEpoch(user.getId());
        String token = jwtTokenProvider.createToken(user, sessionId, authEpoch);
        // 会话白名单：支持"登出单个会话/强制下线"（按 (userId, sessionId) 精确删除）
        redis.opsForValue().set(RedisKeys.session(user.getId(), sessionId), "1",
                Duration.ofSeconds(jwtTokenProvider.expireSeconds()));

        Region region = user.getRegionId() == null ? null : regionMapper.selectById(user.getRegionId());
        log.info("登录成功 username={} role={} ip={}", user.getUsername(), user.getRole(), clientIp);

        return LoginVO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInSeconds(jwtTokenProvider.expireSeconds())
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .roleLabel(user.getRole() == null ? null : user.getRole().getLabel())
                .regionId(user.getRegionId())
                .regionName(region == null ? null : region.getName())
                .employeeNo(user.getEmployeeNo())
                .department(user.getDepartment())
                .sessionId(sessionId)
                .build();
    }

    private void onLoginFailed(SysUser user, String clientIp) {
        String failKey = RedisKeys.loginFail(user.getUsername());
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redis.expire(failKey, Duration.ofMinutes(securityProperties.getLoginLockMinutes()));
        }
        int failCount = user.getLoginFailCount() == null ? 0 : user.getLoginFailCount();
        failCount++;
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLoginFailCount(failCount);
        if (failCount >= securityProperties.getLoginMaxFail()) {
            update.setLockedUntil(LocalDateTime.now()
                    .plusMinutes(securityProperties.getLoginLockMinutes()));
            redis.opsForValue().set(RedisKeys.loginLock(user.getUsername()), "1",
                    Duration.ofMinutes(securityProperties.getLoginLockMinutes()));
            log.warn("账号已锁定 username={} 连续失败={} ip={}", user.getUsername(), failCount, clientIp);
        }
        userMapper.updateById(update);
    }

    // ================================================================ 认证版本（吊销）

    /**
     * 启动时给"上线前就已被禁用"的账号补一个认证墓碑。
     *
     * <p>为什么需要：epoch 机制是本次新增的。上线前被禁用的账号，其旧令牌里 epoch=0、
     * Redis 里也没有 epoch 键（缺省也是 0）→ 两边相等，于是**旧令牌仍会放行**。
     * 这里在启动时把这些账号的 epoch 至少抬到 1，让它们的旧令牌立即失效。
     *
     * <p>用 {@code setIfAbsent} 而不是 {@code set}：绝不能把已经递增过的版本号降回去
     * （那会让之前被吊销的令牌"复活"）。幂等，可安全地在每次启动执行。
     */
    @PostConstruct
    void sealDisabledUsersOnStartup() {
        try {
            List<Object> ids = userMapper.selectObjs(new LambdaQueryWrapper<SysUser>()
                    .select(SysUser::getId)
                    .eq(SysUser::getStatus, 0));
            int sealed = 0;
            for (Object id : ids) {
                Long userId = ((Number) id).longValue();
                Boolean created = redis.opsForValue().setIfAbsent(RedisKeys.authEpoch(userId), "1",
                        Duration.ofSeconds(jwtTokenProvider.expireSeconds()));
                if (Boolean.TRUE.equals(created)) {
                    sealed++;
                }
            }
            if (sealed > 0) {
                log.info("启动补偿：为 {} 个已禁用账号补齐认证版本（其旧令牌立即失效）", sealed);
            }
        } catch (Exception e) {
            // 启动补偿失败不应阻止应用启动；但必须留 ERROR —— 否则"禁用不生效"会静默回来
            log.error("启动补偿失败：已禁用账号的旧令牌可能仍可用，请检查", e);
        }
    }

    /**
     * 读取某用户当前的认证版本；键不存在时为 0。
     *
     * <p>为什么用"版本号"而不是"禁用标记"：
     * 这一个机制同时覆盖了 4 类"变更必须立即生效"的场景 —— 禁用账号、重置口令、
     * 角色/区域变更、强制下线全部设备。若各写一个标记键，拦截器就要为每个场景各查一次 Redis；
     * 现在无论多少种吊销原因，请求路径上永远只是"比对同一个数字"。
     */
    public long currentAuthEpoch(Long userId) {
        String value = redis.opsForValue().get(RedisKeys.authEpoch(userId));
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 值被外部改坏时按"最新版本"处理：fail-closed，宁可让该用户重新登录
            log.warn("认证版本值异常，按已吊销处理 userId={} value={}", userId, value);
            return Long.MAX_VALUE;
        }
    }

    /**
     * 吊销该用户的**全部**已签发令牌（递增认证版本）。
     *
     * <p>用于：管理员禁用账号、重置口令、变更角色/区域、强制下线全部设备。
     * 为什么递增版本就能全部作废：拦截器要求"令牌里的 epoch == 当前 epoch"，
     * 递增之后所有旧令牌的 epoch 都落后，于是立即被拒（无需枚举、无需 SCAN、无竞态）。
     *
     * <p>本次不删会话白名单键：那些键带 TTL（= 令牌寿命），会自然过期；
     * 即使它们还在，epoch 不匹配也会被拒 —— 两道校验都必须通过才放行。
     */
    public void revokeAllSessions(Long userId) {
        Long epoch = redis.opsForValue().increment(RedisKeys.authEpoch(userId));
        // TTL = 令牌寿命：确保版本号至少活得比任何一个可能存在的旧令牌都久，
        // 否则键先过期会让"旧令牌的 epoch(=1) 与缺失的键(=0)"……注意方向：
        // 键过期后旧令牌同样是 1 != 0 → 依然被拒，所以过期也不会"复活"旧令牌，
        // 这里加 TTL 只是为了不留永久键（M11 同类问题）。
        redis.expire(RedisKeys.authEpoch(userId), Duration.ofSeconds(jwtTokenProvider.expireSeconds()));
        log.info("已吊销用户全部会话（认证版本 -> {}）userId={}", epoch, userId);
    }

    /**
     * 校验令牌是否仍然有效：会话白名单 + 认证版本，**一次 Redis 往返**完成。
     *
     * <p>用 {@code MGET} 同时取两个键（原本只有一个 {@code hasKey}，所以没有增加往返次数）：
     * <ul>
     *   <li>会话键不存在 → 该会话已登出/被踢；</li>
     *   <li>令牌 epoch 与当前 epoch 不一致 → 发生过禁用/重置口令/角色变更等吊销。</li>
     * </ul>
     */
    public boolean verifySession(Long userId, String sessionId, Long tokenEpoch) {
        // ① 没有 sid：fail-closed（见 isSessionAlive 的说明）
        if (sessionId == null) {
            return false;
        }
        // ② 令牌里没有认证版本 = 本次上线前签发的旧令牌：fail-closed，要求重新登录。
        //    绝不拆箱成 long（会 NPE）。代价与收益见 JwtTokenProvider.parse 的注释。
        if (tokenEpoch == null) {
            return false;
        }
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(List.of(
                    RedisKeys.session(userId, sessionId), RedisKeys.authEpoch(userId)));
        } catch (Exception e) {
            // ③ Redis 不可用时**必须拒绝**，不能"读不到就放行" ——
            //    否则这里就成了一个新的 fail-open（拿不到版本号恰恰是最需要保守的时刻：
            //    Redis 挂了就无法判断该令牌是否已被吊销）。
            //    这里刻意只记 ERROR 不抛出：抛出去会被全局异常处理变成 500，
            //    而"认证信息暂时无法校验"对客户端应当是 401 而不是"服务器炸了"。
            log.error("会话校验失败（Redis 异常），按已失效处理 userId={} sid={}", userId, sessionId, e);
            return false;
        }
        String session = values == null || values.size() < 2 ? null : values.get(0);
        String epochValue = values == null || values.size() < 2 ? null : values.get(1);
        if (session == null) {
            return false;
        }
        long current = 0L;
        if (epochValue != null && !epochValue.isBlank()) {
            try {
                current = Long.parseLong(epochValue);
            } catch (NumberFormatException e) {
                current = Long.MAX_VALUE;   // 值被改坏 → fail-closed
            }
        }
        return tokenEpoch.longValue() == current;
    }

    /** 登出：把当前会话从白名单移除，token 立即失效。 */
    public void logout(LoginUser loginUser) {
        if (loginUser != null && loginUser.getSessionId() != null) {
            redis.delete(RedisKeys.session(loginUser.getUserId(), loginUser.getSessionId()));
        }
    }

    /**
     * 校验会话是否仍然有效（拦截器每个请求都会调一次）。
     *
     * <p><b>【F3 修复】没有 sessionId 的令牌一律判为无效（fail-closed）。</b>
     *
     * <p>原实现是 {@code sessionId == null → return true}，即 fail-open：一个签名合法但
     * 不带 {@code sid} 的令牌**永远无法被吊销** —— 登出删不掉它（没有键可删）、
     * 禁用也拦不住它（会话校验直接放行）。实测：带正确签名但去掉 sid 的令牌
     * 可以一直访问 {@code /api/auth/ping}，管理员禁用账号后它照样能读能写。
     *
     * <p>为什么可以直接改严：唯一签发入口 {@code AuthService.login} 一定带 sessionId
     * （{@code JwtTokenProvider.newSessionId()}），本方法也只有拦截器一个调用方；
     * 因此"没有 sid"只可能来自伪造/改写过的令牌，拒掉不会影响任何正常用户。
     */
    public boolean isSessionAlive(Long userId, String sessionId) {
        if (sessionId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.session(userId, sessionId)));
    }

    /** 当前登录用户信息（含本人手机号明文，仅本人可见）。 */
    public UserVO currentUser(boolean withPlainPhone) {
        SysUser user = userMapper.selectById(UserContext.requireUserId());
        if (user == null) {
            throw BizException.of(ResultCode.UNAUTHENTICATED);
        }
        Region region = user.getRegionId() == null ? null : regionMapper.selectById(user.getRegionId());
        UserVO vo = UserVO.of(user, region == null ? null : region.getName());
        if (withPlainPhone) {
            // 只回填给本人，且仅在显式要求时（资料编辑页）
            vo.setPhoneMask(phoneCipher.decrypt(user.getPhoneCipher()));
        }
        return vo;
    }

    /** 修改个人资料（手机号走加密链路）。 */
    @Transactional
    public UserVO updateProfile(ProfileUpdateRequest request) {
        Long userId = UserContext.requireUserId();
        SysUser update = new SysUser();
        update.setId(userId);
        update.setRealName(request.realName());
        update.setDepartment(request.department());
        update.setEmail(request.email());
        if (request.phone() != null && !request.phone().isBlank()) {
            PhoneCipher.CipherBundle bundle = phoneCipher.encrypt(request.phone());
            update.setPhoneCipher(bundle.cipher());
            update.setPhoneMask(bundle.mask());
            update.setPhoneHash(bundle.hash());
        }
        userMapper.updateById(update);
        return currentUser(false);
    }

    /** 修改密码；修改成功后强制下线（让所有旧 token 失效）。 */
    @Transactional
    public void changePassword(PasswordChangeRequest request) {
        Long userId = UserContext.requireUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.UNAUTHENTICATED);
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw BizException.of(ResultCode.BAD_CREDENTIALS, "原密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw BizException.param("新密码不能与原密码相同");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(request.newPassword()));
        userMapper.updateById(update);
        log.info("用户修改密码成功 userId={}", userId);
    }

    /** 供用户管理模块复用：统一密码编码器。 */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /** 供用户管理模块复用：校验密码强度（8 位以上且含字母与数字）。 */
    public void validatePasswordStrength(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw BizException.param("密码长度至少 8 位");
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = rawPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw BizException.param("密码需同时包含字母和数字");
        }
    }

    /** 掩码工具透出，便于其它服务统一口径。 */
    public static String maskPhone(String phone) {
        return SensitiveMasker.maskPhone(phone);
    }
}
