package cn.ld.cloud.service.service;

import cn.ld.cloud.api.RandomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * @author lidong
 * @date 2021/1/11
 */
@Slf4j
@RestController
public class RandomServiceImpl implements RandomService {

    @Override
    public Integer getRandomNum() {
        return ThreadLocalRandom.current().nextInt(10000);
    }
}
