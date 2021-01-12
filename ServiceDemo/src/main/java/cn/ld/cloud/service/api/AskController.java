package cn.ld.cloud.service.api;

import cn.ld.cloud.service.service.RandomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;

/**
 * @author lidong
 * @date 2021/1/11
 */
@Slf4j
@RestController
@RequestMapping("/ask")
public class AskController {

    @Resource
    private RestTemplate restTemplate;
    @Resource
    private RandomService randomService;

    @GetMapping("/askRandomNum")
    public int askRandomNum() {
        return randomService.getRandomNum();
    }
}
