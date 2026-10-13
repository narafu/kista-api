package com.kista.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

// kista-api·kista-scheduler 프로세스 부팅 진입점 — 루트 패키지 com.kista.api에 두어 스캔이 기본값(자기 루트)으로
// root 전체를 덮는다. :shared 빈은 PlatformAutoConfiguration이 등록하고, Modulith 검증 모델에만 shared를 포함한다
@SpringBootApplication
@Modulithic(additionalPackages = "com.kista.shared")
@EnableScheduling
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
