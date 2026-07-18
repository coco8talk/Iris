package com.iris.gateway;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.iris.gateway.changes.model.entity.ChangeEvent;
import com.iris.gateway.changes.service.ChangeEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * query_changes 接口测试：查询/过滤/window 校验/鉴权头/背景种子(T17)。
 *
 * <p>每个用例前清空并重新灌入 6 条背景种子，保证计数断言确定。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
@SpringBootTest(properties = {
        "gateway.token=test-token",
        "gateway.cmdb-file=../deploy/cmdb.yaml",
        "spring.datasource.url=jdbc:sqlite:target/changes-test.db"
})
@AutoConfigureMockMvc
class ChangesControllerTest {

    private static final String ENDPOINT = "/api/v1/tools/query_changes";
    private static final String INTERNAL = "/internal/changes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChangeEventService changeEventService;

    @BeforeEach
    void resetSeeds() {
        changeEventService.remove(new QueryWrapper<>());
        changeEventService.seedBackground();
    }

    /** 带合法工具鉴权头的请求 */
    private MockHttpServletRequestBuilder authedPost(String path, String body) {
        return post(path)
                .header("Authorization", "Bearer test-token")
                .header("X-Incident-Id", "INC-CHG-1")
                .header("X-Agent-Role", "lead")
                .contentType("application/json")
                .content(body);
    }

    /** 冷启动种子恰 6 条：window=6h 覆盖全部背景种子 */
    @Test
    void querySixBackgroundSeeds() throws Exception {
        mockMvc.perform(authedPost(ENDPOINT, "{\"window\":\"6h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.data.changes").isArray())
                .andExpect(jsonPath("$.data.changes.length()").value(6))
                .andExpect(jsonPath("$.data.changes[0].change_id").exists())
                .andExpect(jsonPath("$.data.changes[0].operator").exists())
                .andExpect(jsonPath("$.meta.budget_remaining").value(-1));
    }

    /** 带 service 过滤：order-service 恰 2 条种子 */
    @Test
    void queryFilteredByService() throws Exception {
        mockMvc.perform(authedPost(ENDPOINT, "{\"window\":\"6h\",\"service\":\"order-service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(2))
                .andExpect(jsonPath("$.data.changes[0].service").value("order-service"));
    }

    /** window 越界(非枚举值)返回 400 INVALID_PARAM */
    @Test
    void outOfRangeWindowShouldReturn400() throws Exception {
        mockMvc.perform(authedPost(ENDPOINT, "{\"window\":\"12h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    /** 缺失 X-Incident-Id 头返回 400（工具接口强制该头） */
    @Test
    void missingIncidentHeaderShouldReturn400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer test-token")
                        .header("X-Agent-Role", "lead")
                        .contentType("application/json")
                        .content("{\"window\":\"6h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    /** 内部写入：仅需 Bearer、无需 incident 头，写入后可被 query_changes 查到 */
    @Test
    void internalWriteThenQuery() throws Exception {
        mockMvc.perform(post(INTERNAL)
                        .header("Authorization", "Bearer test-token")
                        .contentType("application/json")
                        .content("{\"type\":\"config_change\",\"service\":\"payment-service\","
                                + "\"summary\":\"payment channel timeout 3000->500\",\"operator\":\"injector\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.change_id").exists());

        mockMvc.perform(authedPost(ENDPOINT, "{\"window\":\"6h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes.length()").value(7));
    }

    /** 内部写入缺 Bearer token 返回 401 */
    @Test
    void internalWriteWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(post(INTERNAL)
                        .contentType("application/json")
                        .content("{\"type\":\"deploy\",\"service\":\"gateway\","
                                + "\"summary\":\"x\",\"operator\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }
}
