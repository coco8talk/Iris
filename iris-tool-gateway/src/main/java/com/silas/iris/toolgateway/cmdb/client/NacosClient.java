package com.silas.iris.toolgateway.cmdb.client;

import com.silas.iris.toolgateway.cmdb.constant.NacosConstants;
import com.silas.iris.toolgateway.cmdb.model.dto.NacosResponseDTO;
import com.silas.iris.toolgateway.cmdb.model.dto.NacosServicePageDTO;
import com.silas.iris.toolgateway.cmdb.model.vo.ServiceTopologyVO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Nacos HTTP 访问层：通过 Console API 拉取完整服务列表，通过 8848 Client API 查询实例拓扑。
 * 上游 HTTP 异常直接交给全局异常处理器。
 *
 * @author silas
 * @since 2026/8/2
 */
@Slf4j
@Component
public class NacosClient {

    @Value("${iris.nacos.base-url:http://localhost:8848}")
    private String nacosBaseUrl;

    @Value("${iris.nacos.console-base-url:http://localhost:8080}")
    private String nacosConsoleBaseUrl;

    @Value("${iris.nacos.namespace-id:public}")
    private String namespaceId;

    @Value("${iris.nacos.group-name:DEFAULT_GROUP}")
    private String groupName;

    @Value("${iris.nacos.connect-timeout-ms:3000}")
    private int nacosConnectTimeoutMs;

    @Value("${iris.nacos.read-timeout-ms:10000}")
    private int nacosReadTimeoutMs;

    private RestClient nacosRestClient;
    private RestClient nacosConsoleRestClient;

    /**
     * 初始化 Nacos HTTP 客户端，在依赖注入结束后由 Spring 自动调用一次。
     */
    @PostConstruct
    void initNacosClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(nacosConnectTimeoutMs);
        requestFactory.setReadTimeout(nacosReadTimeoutMs);
        this.nacosRestClient = RestClient.builder()
                .baseUrl(nacosBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.nacosConsoleRestClient = RestClient.builder()
                .baseUrl(nacosConsoleBaseUrl)
                .requestFactory(requestFactory)
                .build();
        log.info("Nacos 客户端初始化完成，connectTimeoutMs: {}, readTimeoutMs: {}",
                nacosConnectTimeoutMs, nacosReadTimeoutMs);
    }

    /**
     * 按 Nacos 单页上限分页拉取当前 namespace/group 下的全部服务名。
     *
     * @return 去重后的服务名集合
     */
    public Set<String> listServiceNames() {
        Set<String> serviceNames = new LinkedHashSet<>();
        int pageNo = 1;
        int pagesAvailable;
        do {
            int requestedPage = pageNo;
            NacosResponseDTO<NacosServicePageDTO> response = nacosConsoleRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(NacosConstants.SERVICE_LIST_PATH)
                            .queryParam("namespaceId", namespaceId)
                            .queryParam("groupNameParam", groupName)
                            .queryParam("pageNo", requestedPage)
                            .queryParam("pageSize", NacosConstants.SERVICE_PAGE_SIZE)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            NacosServicePageDTO page = requireSuccess(response, "查询服务列表");
            if (page.getPageItems() != null) {
                page.getPageItems().stream()
                        .map(NacosServicePageDTO.ServiceItem::getName)
                        .filter(Objects::nonNull)
                        .forEach(serviceNames::add);
            }
            pagesAvailable = Objects.requireNonNullElse(page.getPagesAvailable(), 0);
            pageNo++;
        } while (pageNo <= pagesAvailable);
        return Set.copyOf(serviceNames);
    }

    /**
     * 查询指定服务的全部实例，并组装对外拓扑对象。
     *
     * @param service 服务名
     * @return 服务实例拓扑
     */
    public ServiceTopologyVO queryTopology(String service) {
        NacosResponseDTO<List<ServiceTopologyVO.Instance>> response = nacosRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(NacosConstants.INSTANCE_LIST_PATH)
                        .queryParam("namespaceId", namespaceId)
                        .queryParam("groupName", groupName)
                        .queryParam("serviceName", service)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        List<ServiceTopologyVO.Instance> instances = requireSuccess(response, "查询服务实例列表");
        return ServiceTopologyVO.builder()
                .service(service)
                .groupName(groupName)
                .instances(List.copyOf(instances))
                .build();
    }

    /**
     * 校验 Nacos 统一响应码，并返回 data。
     */
    private <T> T requireSuccess(NacosResponseDTO<T> response, String operation) {
        if (response == null || !Objects.equals(response.getCode(), NacosConstants.SUCCESS_CODE)) {
            String message = response == null ? "响应为空" : response.getMessage();
            throw new IllegalStateException(operation + "失败: " + message);
        }
        if (response.getData() == null) {
            throw new IllegalStateException(operation + "失败: data 为空");
        }
        return response.getData();
    }
}
