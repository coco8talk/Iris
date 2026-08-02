package com.silas.iris.toolgateway.cmdb.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nacos 3.x Open API 统一响应结构。
 *
 * @param <T> data 字段的实际类型
 * @author silas
 * @since 2026/8/2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NacosResponseDTO<T> {

    private Integer code;

    private String message;

    private T data;
}
