package com.zkjs.aio.swap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.zkjs.aio.swap.mapper")
@SpringBootApplication
public class WspSwapApplication {

    public static void main(String[] args) {
        SpringApplication.run(WspSwapApplication.class, args);
    }

}
