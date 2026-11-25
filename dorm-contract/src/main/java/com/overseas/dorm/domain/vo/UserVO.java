package com.overseas.dorm.domain.vo;

import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.domain.entity.SysUser;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图。
 *
 * <p>手机号只提供掩码，这是「列表接口泄露 PII」最常见的翻车点：
 * 开发时为图方便直接返回 {@code user} 实体，于是所有人的手机号
 * 就被明文塞进了一个可能有几百条记录的 JSON 里。
 */
@Data
public class UserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String realName;
    private String employeeNo;
    private UserRole role;
    private String roleLabel;
    private Long regionId;
    private String regionName;
    private String department;
    /** 仅掩码，如 138****8000。 */
    private String phoneMask;
    private String email;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;

    public static UserVO of(SysUser user, String regionName) {
        UserVO vo = new UserVO();
        vo.id = user.getId();
        vo.username = user.getUsername();
        vo.realName = user.getRealName();
        vo.employeeNo = user.getEmployeeNo();
        vo.role = user.getRole();
        vo.roleLabel = user.getRole() == null ? null : user.getRole().getLabel();
        vo.regionId = user.getRegionId();
        vo.regionName = regionName;
        vo.department = user.getDepartment();
        vo.phoneMask = user.getPhoneMask();
        vo.email = user.getEmail();
        vo.status = user.getStatus();
        vo.lastLoginTime = user.getLastLoginTime();
        vo.createTime = user.getCreateTime();
        return vo;
    }
}
