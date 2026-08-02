package com.silas.iris.toolgateway.common.web;

import com.silas.iris.toolgateway.common.exception.ToolCallsExceededException;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnTooManyRequestsWhenToolCallsExceeded() {
        ResponseEntity<ApiEnvelope<?>> response =
                handler.handleToolCallsExceeded(new ToolCallsExceededException("INC-001"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isOk());
        assertFalse(response.getBody().isDegraded());
        assertEquals("INC-001", response.getBody().getMessage());
    }
}
