package com.overseas.dorm.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * AI Agent 服务启动类。
 *
 * <p>本服务只拥有 Agent 库（会话、消息、工具审计、用量、记忆、RAG），
 * 不连接业务库。所有房源/申请/订单/用户数据通过 {@code DormServiceClient}
 * 调用 dorm-service 的 {@code /internal/**} 获取。
 */
@SpringBootApplication(scanBasePackages = "com.overseas.dorm")
@ConfigurationPropertiesScan
@MapperScan("com.overseas.dorm.domain.mapper")
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
