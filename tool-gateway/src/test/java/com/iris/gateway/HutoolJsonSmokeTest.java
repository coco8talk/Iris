package com.iris.gateway;

import cn.hutool.json.JSONException;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.iris.gateway.cmdb.model.dto.CmdbQueryDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * hutool-json 冒烟测试：验证依赖可用及解析成功/失败行为
 *
 * @author silas
 * @since 2026/7/14
 */
class HutoolJsonSmokeTest {

    /** 合法 JSON 解析成功 */
    @Test
    void parseObjShouldSucceedOnValidJson() {
        JSONObject obj = JSONUtil.parseObj("{\"template\":\"get_topology\",\"service_name\":null}");
        assertThat(obj.getStr("template")).isEqualTo("get_topology");
    }

    /** DTO 序列化为 JSON 字符串(CmdbController 审计 paramsJson 用法) */
    @Test
    void toJsonStrShouldSerializeDto() {
        String json = JSONUtil.toJsonStr(new CmdbQueryDTO("get_service_detail", "order-service"));
        JSONObject obj = JSONUtil.parseObj(json);
        assertThat(obj.getStr("template")).isEqualTo("get_service_detail");
        assertThat(obj.getStr("serviceName")).isEqualTo("order-service");
    }

    /** 非法 JSON 解析失败抛出 JSONException */
    @Test
    void parseObjShouldFailOnInvalidJson() {
        assertThatThrownBy(() -> JSONUtil.parseObj("not-a-json"))
                .isInstanceOf(JSONException.class);
    }
}
