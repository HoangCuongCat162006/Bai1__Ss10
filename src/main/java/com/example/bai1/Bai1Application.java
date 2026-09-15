package com.example.bai1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
        "org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration"
})
public class Bai1Application {

    public static void main(String[] args) {
        SpringApplication.run(Bai1Application.class, args);
    }

}
