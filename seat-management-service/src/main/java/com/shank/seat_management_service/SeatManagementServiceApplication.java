package com.shank.seat_management_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeatManagementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeatManagementServiceApplication.class, args);
    }
}