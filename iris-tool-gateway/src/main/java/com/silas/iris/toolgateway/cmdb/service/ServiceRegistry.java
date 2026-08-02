package com.silas.iris.toolgateway.cmdb.service;

import com.silas.iris.toolgateway.cmdb.client.NacosClient;
import com.silas.iris.toolgateway.cmdb.model.vo.ServiceTopologyVO;
import com.silas.iris.toolgateway.common.exception.UnknownServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 基于 Nacos 服务目录的动态白名单，使用惰性 TTL 内存缓存减少列表查询次数。
 *
 * @author silas
 * @since 2026/8/2
 */
@Slf4j
@Service
public class ServiceRegistry {

    private final NacosClient nacosClient;
    private final Object refreshMonitor = new Object();

    @Value("${iris.nacos.service-cache-ttl-seconds:30}")
    private long serviceCacheTtlSeconds;

    private volatile ServiceCache serviceCache = new ServiceCache(Set.of(), 0L);

    public ServiceRegistry(NacosClient nacosClient) {
        this.nacosClient = nacosClient;
    }

    /**
     * 要求 service 存在于当前 Nacos 服务目录，未登记时抛出 400 映射所需异常。
     *
     * @param service 服务名
     * @throws UnknownServiceException service 未登记
     */
    public void requireExists(String service) {
        if (!currentServiceNames().contains(service)) {
            throw new UnknownServiceException(service);
        }
    }

    /**
     * 校验服务白名单后查询实例拓扑。
     *
     * @param service 服务名
     * @return 服务实例拓扑
     */
    public ServiceTopologyVO queryTopology(String service) {
        requireExists(service);
        return nacosClient.queryTopology(service);
    }

    /**
     * 返回当前有效缓存；首次调用或 TTL 到期时由首个进入的线程同步刷新。
     */
    private Set<String> currentServiceNames() {
        long now = System.nanoTime();
        ServiceCache current = serviceCache;
        if (now < current.expiresAtNanos()) {
            return current.serviceNames();
        }

        synchronized (refreshMonitor) {
            now = System.nanoTime();
            current = serviceCache;
            if (now < current.expiresAtNanos()) {
                return current.serviceNames();
            }

            Set<String> serviceNames = nacosClient.listServiceNames();
            long expiresAtNanos = now + Duration.ofSeconds(serviceCacheTtlSeconds).toNanos();
            serviceCache = new ServiceCache(serviceNames, expiresAtNanos);
            log.info("Nacos 服务白名单缓存刷新完成，serviceCount: {}, ttlSeconds: {}",
                    serviceNames.size(), serviceCacheTtlSeconds);
            return serviceNames;
        }
    }

    /**
     * 不可变的服务白名单缓存快照。
     */
    private record ServiceCache(Set<String> serviceNames, long expiresAtNanos) {
    }
}
