package com.web.backen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackenApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackenApplication.class, args);
    }
}
