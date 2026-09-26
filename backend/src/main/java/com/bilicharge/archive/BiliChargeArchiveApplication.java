package com.bilicharge.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BiliChargeArchiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(BiliChargeArchiveApplication.class, args);
    }
}
