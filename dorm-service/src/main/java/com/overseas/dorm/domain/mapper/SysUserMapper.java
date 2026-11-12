package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户数据访问。
 *
 * <p>注意按手机号检索走的是 {@code phone_hash}（HMAC 摘要）而不是密文：
 * AES-GCM 每次加密的 IV 随机，同一手机号密文各不相同，无法直接按密文查询。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {



}
