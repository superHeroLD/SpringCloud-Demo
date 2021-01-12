package cn.ld.cloud.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

/**
 * @author lidong
 * @date 2021/1/11
 */
@EnableEurekaClient
@SpringBootApplication
public class AnotherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnotherServiceApplication.class, args);
    }
}
