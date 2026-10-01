package com.kart.track;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class KartTrackApplication {

    public static void main(String[] args) {
        SpringApplication.run(KartTrackApplication.class, args);
    }
}
