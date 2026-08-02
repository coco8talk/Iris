package com.silas.iris.toolgateway.cmdb.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Nacos 服务列表 Admin API 的分页数据结构。
 *
 * @author silas
 * @since 2026/8/2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NacosServicePageDTO {

    private Integer totalCount;

    private Integer pageNumber;

    private Integer pagesAvailable;

    private List<ServiceItem> pageItems;

    /**
     * 服务列表中的单条服务摘要。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceItem {

        private String name;

        private String groupName;
    }
}
