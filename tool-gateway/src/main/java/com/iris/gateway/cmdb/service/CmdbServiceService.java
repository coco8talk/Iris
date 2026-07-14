package com.iris.gateway.cmdb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.iris.gateway.cmdb.model.entity.CmdbServiceEntity;
import com.iris.gateway.cmdb.vo.CmdbServiceVO;

import java.util.List;

/**
 * CMDB 服务记录读写：启动时全量重建，运行期只读查询
 *
 * @author silas
 * @since 2026/7/14
 */
public interface CmdbServiceService extends IService<CmdbServiceEntity> {

    /**
     * 全量重建 CMDB 数据：清空后重新写入，保证启动幂等(T17)。
     *
     * @param services cmdb.yaml 解析出的全部服务记录
     */
    void reload(List<CmdbServiceEntity> services);

    /**
     * 查询全部服务记录。
     *
     * @return 全部服务视图，record_json 已反序列化
     */
    List<CmdbServiceVO> listAllVo();

    /**
     * 按服务名查询。
     *
     * @param name 服务名（主键）
     * @return 服务视图；不存在时返回 null
     */
    CmdbServiceVO getVoByName(String name);
}
