package com.silas.iris.toolgateway.cmdb.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 指定服务在 Nacos 中的实例拓扑。
 *
 * @author silas
 * @since 2026/8/2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Nacos 服务实例拓扑")
public class ServiceTopologyVO {

    @Schema(description = "服务名", example = "pm-auth")
    private String service;

    @Schema(description = "Nacos 服务分组", example = "DEFAULT_GROUP")
    private String groupName;

    @Schema(description = "服务实例列表")
    private List<Instance> instances;

    /**
     * 单个 Nacos 服务实例的拓扑信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Nacos 服务实例")
    public static class Instance {

        @Schema(description = "实例 IP", example = "127.0.0.1")
        private String ip;

        @Schema(description = "实例端口", example = "8080")
        private Integer port;

        @Schema(description = "实例所在集群", example = "DEFAULT")
        private String clusterName;

        @Schema(description = "实例是否健康")
        private Boolean healthy;

        @Schema(description = "实例是否启用")
        private Boolean enabled;

        @Schema(description = "是否为临时实例")
        private Boolean ephemeral;

        @Schema(description = "实例权重")
        private Double weight;

        @Schema(description = "实例元数据")
        private Map<String, String> metadata;
    }
}
