package com.kista.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

// kista-web·kista-worker 진입점 — 한 애플리케이션이 kista.role로 역할을 나눈다. root(com.kista.api)와 매매 엔진(com.kista.tradingcore)을
// 함께 스캔하고, :shared 빈은 PlatformAutoConfiguration이 등록한다(스캔하지 않는다). Modulith 검증 모델에는 shared·tradingcore를 포함한다
@SpringBootApplication(scanBasePackages = {"com.kista.api", "com.kista.tradingcore"})
// 엔티티·JPA 리포지토리 자동구성 범위에 매매 엔진 패키지를 더한다 — @EntityScan·@EnableJpaRepositories를 여기 두면
// @WebMvcTest 슬라이스도 리포지토리를 만들려다 entityManagerFactory 없이 실패한다(자동구성 패키지는 JPA 자동구성이 켜질 때만 쓰인다)
@AutoConfigurationPackage(basePackages = {"com.kista.api", "com.kista.tradingcore"}) // 같은 Registrar import라 기본값(자기 패키지)을 대체한다 — 둘 다 적는다
@ConfigurationPropertiesScan("com.kista.tradingcore") // trading-core 소유 @ConfigurationProperties
@Modulithic(additionalPackages = {"com.kista.shared", "com.kista.tradingcore"})
@EnableScheduling
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
