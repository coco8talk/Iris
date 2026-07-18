package com.iris.gateway;

import com.iris.gateway.logs.LokiClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LogQL 构造：label selector 强制、level/keyword 行过滤、keyword 转义(T15)。
 *
 * @author silas
 * @since 2026/7/18
 */
class LogsQueryBuilderTest {

    @Test
    void selectorOnly() {
        assertThat(LokiClient.buildLogQL("order-service", null, null))
                .isEqualTo("{container=\"order-service\"}");
    }

    @Test
    void selectorWithLevel() {
        assertThat(LokiClient.buildLogQL("order-service", "ERROR", null))
                .isEqualTo("{container=\"order-service\"} |= \"ERROR\"");
    }

    @Test
    void selectorWithLevelAndKeyword() {
        assertThat(LokiClient.buildLogQL("order-service", "ERROR", "timeout"))
                .isEqualTo("{container=\"order-service\"} |= \"ERROR\" |= \"timeout\"");
    }

    @Test
    void keywordQuotesAndBackslashesEscaped() {
        assertThat(LokiClient.buildLogQL("order-service", null, "say \"hi\" \\n"))
                .isEqualTo("{container=\"order-service\"} |= \"say \\\"hi\\\" \\\\n\"");
    }
}
