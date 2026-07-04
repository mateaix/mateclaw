package vip.mate.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 全局配置。
 * <p>
 * 项目已依赖 springdoc-openapi-starter-webmvc-ui（见根 pom 的
 * {@code springdoc.version}），但此前没有任何 OpenAPI 配置 Bean，导致
 * Swagger UI 缺少标题/描述、缺少安全方案（Authorize 按钮不可用）。
 * 本类补齐这些全局元信息，让现有 Controller 上已有的
 * {@code @Tag} / {@code @Operation} 注解直接可用。
 *
 * <h3>安全方案</h3>
 * 两种 token 都通过标准 {@code Authorization: Bearer <token>} 头传入，
 * {@link JwtAuthFilter} 按 token 前缀分发：JWT 以 {@code eyJ} 开头，
 * Personal Access Token 以 {@code mc_} 开头（PAT_PREFIX）。因此 Swagger
 * UI 的 Authorize 按钮只需填入任意一种 token 即可。
 *
 * <h3>访问控制（在 SecurityConfig，不在本类）</h3>
 * Swagger UI / OpenAPI 文档路径（{@code /swagger-ui*}、{@code /v3/api-docs*}、
 * {@code /webjars/**}）的鉴权由 {@link SecurityConfig#filterChain} 通过
 * {@code mateclaw.openapi.expose-ui} 开关控制：本地/默认 profile 公开，
 * 生产数据库 profile（mysql/kingbase/postgres）默认要求全局管理员
 * （{@code ROLE_ADMIN}）。访问规则只属于 SecurityConfig，不要加到本类。
 *
 * <h3>未做的事（与「全局配置 + 安全方案」范围一致）</h3>
 * 不逐个 Controller 补 {@code @Parameter} / {@code @ApiResponse} /
 * {@code @Schema} / 公开端点的 {@code @SecurityRequirements({})} opt-out。
 * 这些属于「关键端点注解」增强档，留作后续。
 *
 * @author MateClaw Team
 */
@Configuration
public class OpenApiConfig {

    /** HTTP Bearer 安全方案的引用键，与 {@link Components#getSecuritySchemes()} 中的登记名一致。 */
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI mateclawOpenAPI(
            @Value("${mateclaw.openapi.title:MateClaw REST API}") String title,
            @Value("${mateclaw.openapi.description:#{null}}") String description,
            @Value("${mateclaw.openapi.version:1.0}") String version,
            @Value("${mateclaw.openapi.server-url:}") String serverUrl) {

        Info info = new Info()
                .title(title)
                .version(version)
                .description(defaultIfBlank(description, ""
                        + "MateClaw 多用户 AI Agent 平台的 REST API。"
                        + "所有业务端点使用 /api/v1 前缀，绝大多数 JSON 响应走 {code,msg,data} 统一信封。"
                        + "点击右上角 Authorize 并粘贴 JWT 或 Personal Access Token (mc_) 即可调试受保护端点。"
                        + "完整人读文档见部署地址的 /docs 页面。"));

        Components components = new Components()
                .addSecuritySchemes(BEARER_AUTH, bearerScheme(
                        "JWT 或 Personal Access Token。两种都通过 Authorization: Bearer <token> 头传入，"
                        + "服务端按前缀分发：JWT 以 eyJ 开头，PAT 以 mc_ 开头。"
                        + "EventSource 不支持自定义请求头，SSE 流式端点可用 ?token=<token> 查询参数替代。"));

        OpenAPI openAPI = new OpenAPI()
                .info(info)
                .components(components)
                // 默认所有端点需要鉴权；公开端点（登录、SSE 等）在 SecurityConfig 中放行，
                // Swagger 上会仍标注锁图标，但不影响实际调用。
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));

        if (serverUrl != null && !serverUrl.isBlank()) {
            openAPI.servers(List.of(new Server().url(serverUrl)));
        }
        // serverUrl 为空时不显式配置 —— SpringDoc 默认从请求 host 推导，
        // 避免「Try it out」打到错误地址。

        return openAPI;
    }

    private SecurityScheme bearerScheme(String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description(description);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
