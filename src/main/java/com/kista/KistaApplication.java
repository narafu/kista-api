package com.kista;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class KistaApplication {
    public static void main(String[] args) {
        // Spring 초기화 전 JVM default TimeZone을 KST로 고정
        // Render 컨테이너 기본 TZ(UTC)에서 LocalDate.now()가 UTC 날짜를 반환해 KIS 휴장 조회 오판 방지
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(KistaApplication.class, args);
    }
}
