package com.silas.iris.toolgateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.silas.iris.toolgateway.common.audit.mapper")
@SpringBootApplication
public class IrisToolGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrisToolGatewayApplication.class, args);
    }

}
