package cn.ld.cloud.service.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @author lidong
 * @date 2021/1/12
 */
@FeignClient("service-provider")
public interface RandomService {

    @GetMapping("/random/getRandomNum")
    Integer getRandomNum();
}
