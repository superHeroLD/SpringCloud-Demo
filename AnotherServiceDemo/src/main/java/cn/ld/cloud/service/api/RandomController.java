package cn.ld.cloud.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

/**
 * @author lidong
 * @date 2021/1/11
 */
@Slf4j
@RestController
@RequestMapping("/random")
public class RandomController {

    @GetMapping("/getRandomNum")
    public int getRandomNum() {
        return ThreadLocalRandom.current().nextInt(10000);
    }
}
