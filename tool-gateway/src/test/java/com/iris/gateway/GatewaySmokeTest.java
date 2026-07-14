package com.iris.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 网关冒烟测试：接口调用、DTO 校验、鉴权拦截、knife4j 文档页
 *
 * @author silas
 * @since 2026/7/14
 */
@SpringBootTest(properties = {
        "gateway.token=test-token",
        "spring.datasource.url=jdbc:sqlite:target/smoke-test.db"
})
@AutoConfigureMockMvc
class GatewaySmokeTest {

    private static final String ENDPOINT = "/api/v1/tools/query_cmdb";

    @Autowired
    private MockMvc mockMvc;

    /** 构造带合法鉴权头的请求 */
    private MockHttpServletRequestBuilder authedPost(String body) {
        return post(ENDPOINT)
                .header("Authorization", "Bearer test-token")
                .header("X-Incident-Id", "INC-TEST-1")
                .header("X-Agent-Role", "lead")
                .contentType("application/json")
                .content(body);
    }

    /** 接口正常调用:查询整体拓扑 */
    @Test
    void queryTopologyShouldReturnServices() throws Exception {
        mockMvc.perform(authedPost("{\"template\":\"get_topology\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.services").isArray())
                .andExpect(jsonPath("$.data.depends_on").exists());
    }

    /** 接口正常调用:查询单一服务详情(service_name 为 snake_case 请求体字段) */
    @Test
    void queryServiceDetailShouldReturnRecord() throws Exception {
        mockMvc.perform(authedPost("{\"template\":\"get_service_detail\",\"service_name\":\"order-service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.name").value("order-service"));
    }

    /** DTO 校验生效:template 为空触发 @NotBlank,返回 400 INVALID_PARAM */
    @Test
    void blankTemplateShouldReturn400() throws Exception {
        mockMvc.perform(authedPost("{\"template\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    /** 业务校验生效:未知 template 返回 400 INVALID_PARAM */
    @Test
    void unknownTemplateShouldReturn400() throws Exception {
        mockMvc.perform(authedPost("{\"template\":\"drop_table\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    /** 非法 JSON 请求体返回 400 而非 500 */
    @Test
    void malformedJsonBodyShouldReturn400() throws Exception {
        mockMvc.perform(authedPost("not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    /** 鉴权拦截生效:缺失 Bearer Token 返回 401 */
    @Test
    void missingTokenShouldReturn401() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType("application/json")
                        .content("{\"template\":\"get_topology\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** knife4j 文档页可正常加载 */
    @Test
    void knife4jDocPageShouldLoad() throws Exception {
        mockMvc.perform(get("/doc.html"))
                .andExpect(status().isOk());
    }

    /** OpenAPI 描述文件可正常生成 */
    @Test
    void openApiDocsShouldLoad() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }
}
