package com.overseas.dorm.boot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置。
 *
 * <p>开发期配合 swagger-ui 可以直接点着调接口，是验证后端最快的方式；
 * 生产环境建议通过 profile 关闭（见 application-prod.yml）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dormOpenApi() {
        final String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("海外宿舍管理系统 API")
                        .version("1.0.0")
                        .description("""
                                企业海外宿舍管理系统：员工出差前提交目的地宿舍入住申请，区域管理员审批并分配房间床位，
                                员工到达后办理入住、到期办理退房；并内置 LangChain4j AI 助手，
                                支持房源咨询、政策咨询、入住申请与订单咨询。

                                认证方式：先调用 /api/auth/login 获取 token，再点右上角 Authorize 填入。
                                """)
                        .contact(new Contact().name("Dorm Platform").email("dorm@example-corp.com")))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
