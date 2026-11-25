package com.overseas.dorm.domain.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * <p>三个插件的取舍：
 * <ul>
 *   <li><b>分页</b>，必须显式指定 DbType，否则 count 语句会走错方言；</li>
 *   <li><b>乐观锁</b>，与 {@code @Version} 配合，用于「读-改-写」场景
 *       （典型：管理员改房间状态），冲突时影响行数为 0，业务层需据此返回 4000；</li>
 *   <li><b>防全表更新删除</b>，这是一个「便宜的安全网」，
 *       能在开发期就把 {@code update(entity, null)} 这类事故挡在测试环境里。</li>
 * </ul>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大 200 条：防止前端传 size=100000 把库拖垮（也是一种防刷）
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /**
     * 公共字段自动填充：create_by / create_time / update_by / update_time。
     *
     * <p>从 {@link UserContext} 取当前用户，因此所有写操作天然带上「谁改的」，
     * 无需在每个 Service 里手动 set，手动 set 一定会有人忘。
     */
    @Component
    public static class AuditMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
            strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
            Long userId = currentUserId();
            if (userId != null) {
                strictInsertFill(metaObject, "createBy", Long.class, userId);
                strictInsertFill(metaObject, "updateBy", Long.class, userId);
            }
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            Long userId = currentUserId();
            if (userId != null) {
                strictUpdateFill(metaObject, "updateBy", Long.class, userId);
            }
        }

        private Long currentUserId() {
            LoginUser user = UserContext.get();
            return user == null ? null : user.getUserId();
        }
    }
}
