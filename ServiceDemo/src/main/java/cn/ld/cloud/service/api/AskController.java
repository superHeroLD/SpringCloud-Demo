package cn.ld.cloud.service.api;

import cn.ld.cloud.api.RandomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * @author lidong
 * @date 2021/1/11
 */
@Slf4j
@RestController
@RequestMapping("/ask")
public class AskController {

    @Autowired
    private RandomService randomService;

    @GetMapping("/askRandomNum")
    public int askRandomNum() {
        return randomService.getRandomNum();
    }
}
