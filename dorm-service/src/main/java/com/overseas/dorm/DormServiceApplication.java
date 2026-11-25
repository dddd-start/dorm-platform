package com.overseas.dorm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 宿舍业务服务启动类。
 *
 * <p>本服务是业务数据的唯一写入方：区域、公寓、房源、床位、入住申请、订单、
 * 用户与审计。它不包含任何模型、Prompt、SSE 代码，AI 能力由 agent-service 提供。
 *
 * <p>对外接口分两类：
 * <ul>
 *   <li>{@code /api/**}，给前端使用，JWT 鉴权；</li>
 *   <li>{@code /internal/**}，给 agent-service 使用，内部令牌 + 用户 JWT 双重校验。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.overseas.dorm.domain.mapper")
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
public class DormServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DormServiceApplication.class, args);
    }
}
