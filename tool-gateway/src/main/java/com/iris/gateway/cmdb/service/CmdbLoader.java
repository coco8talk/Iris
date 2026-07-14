package com.iris.gateway.cmdb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.gateway.cmdb.model.entity.CmdbServiceEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 启动时读取 cmdb.yaml，全量重建 cmdb_service 表（建表见 schema.sql）
 *
 * @author silas
 * @since 2026/7/14
 */
@Slf4j
@Component
public class CmdbLoader implements ApplicationRunner {

    private final CmdbServiceService cmdbServiceService;
    private final ObjectMapper mapper;

    /** cmdb.yaml 文件路径，由环境变量 CMDB_FILE 注入，默认 ../deploy/cmdb.yaml */
    @Value("${gateway.cmdb-file}")
    private String cmdbFile;

    public CmdbLoader(CmdbServiceService cmdbServiceService, ObjectMapper mapper) {
        this.cmdbServiceService = cmdbServiceService;
        this.mapper = mapper;
    }

    /**
     * 解析 cmdb.yaml 的 services 列表，将每个服务整体序列化为 JSON 后全量重建入库；
     * 文件缺失或格式非法时抛异常,启动失败(CMDB 数据是本网关的前提)
     */
    @Override
    @SuppressWarnings("unchecked")
    public void run(ApplicationArguments args) throws Exception {
        Map<String, Object> root;
        try (InputStream in = new FileInputStream(cmdbFile)) {
            root = new Yaml().load(in);
        }
        List<Map<String, Object>> services = (List<Map<String, Object>>) root.get("services");

        List<CmdbServiceEntity> entities = services.stream().map(service -> {
            CmdbServiceEntity entity = new CmdbServiceEntity();
            entity.setName((String) service.get("name"));
            try {
                entity.setRecordJson(mapper.writeValueAsString(service));
            } catch (Exception e) {
                throw new IllegalStateException("failed to serialize service: " + service.get("name"), e);
            }
            return entity;
        }).toList();

        // 幂等重建(T17)
        cmdbServiceService.reload(entities);
        log.info("CMDB loaded: {} services from {}", entities.size(), cmdbFile);
    }
}
