package com.iris.gateway.cmdb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.gateway.cmdb.mapper.CmdbServiceMapper;
import com.iris.gateway.cmdb.model.entity.CmdbServiceEntity;
import com.iris.gateway.cmdb.service.CmdbServiceService;
import com.iris.gateway.cmdb.vo.CmdbServiceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * CMDB 服务记录读写实现，基于 MyBatis-Plus 通用 Service
 *
 * @author silas
 * @since 2026/7/14
 */
@Slf4j
@Service
public class CmdbServiceServiceImpl extends ServiceImpl<CmdbServiceMapper, CmdbServiceEntity> implements CmdbServiceService {

    private final ObjectMapper mapper;

    public CmdbServiceServiceImpl(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void reload(List<CmdbServiceEntity> services) {
        remove(new QueryWrapper<>());
        saveBatch(services);
    }

    @Override
    public List<CmdbServiceVO> listAllVo() {
        return list().stream().map(this::toVo).toList();
    }

    @Override
    public CmdbServiceVO getVoByName(String name) {
        CmdbServiceEntity entity = getById(name);
        return entity == null ? null : toVo(entity);
    }

    /**
     * 实体转视图：反序列化 record_json；数据非法说明入库环节有 bug，直接抛 IllegalStateException
     */
    private CmdbServiceVO toVo(CmdbServiceEntity entity) {
        CmdbServiceVO vo = new CmdbServiceVO();
        vo.setName(entity.getName());
        try {
            vo.setRecord(mapper.readValue(entity.getRecordJson(), new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            throw new IllegalStateException("invalid record_json for service: " + entity.getName(), e);
        }
        return vo;
    }
}
