package com.silas.iris.toolgateway.logs.controller;

import com.silas.iris.toolgateway.cmdb.service.ServiceRegistry;
import com.silas.iris.toolgateway.common.exception.RawQueryRejectedException;
import com.silas.iris.toolgateway.common.redis.RedisService;
import com.silas.iris.toolgateway.logs.constant.LogsQueryConstants;
import com.silas.iris.toolgateway.logs.model.dto.QueryLogsRawDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LogsControllerTest {

    @Test
    void rawLogQlSelectorMustStartQueryAndContainMatcher() {
        assertTrue(LogsQueryConstants.RAW_LOGQL_LABEL_SELECTOR
                .matcher("  {service_name=\"pm-question\"} |= \"ERROR\"").matches());
        assertTrue(LogsQueryConstants.RAW_LOGQL_LABEL_SELECTOR
                .matcher("{namespace=~\"prod|staging\", service_name!=\"\"} | json").matches());
        assertFalse(LogsQueryConstants.RAW_LOGQL_LABEL_SELECTOR
                .matcher("sum(count_over_time({service_name=\"pm-question\"}[5m]))").matches());
        assertFalse(LogsQueryConstants.RAW_LOGQL_LABEL_SELECTOR.matcher("{}").matches());
        assertFalse(LogsQueryConstants.RAW_LOGQL_LABEL_SELECTOR.matcher("ERROR").matches());
    }

    @Test
    void invalidRawLogQlDoesNotConsumeBudget() {
        RedisService redisService = mock(RedisService.class);
        LogsController controller = new LogsController(redisService, mock(ServiceRegistry.class));
        QueryLogsRawDTO dto = new QueryLogsRawDTO();
        dto.setQuery("ERROR");
        dto.setService("pm-question");
        dto.setWindow(300);

        assertThrows(RawQueryRejectedException.class,
                () -> controller.queryRaw(dto, "inc-invalid", new MockHttpServletRequest()));
        verifyNoInteractions(redisService);
    }
}
