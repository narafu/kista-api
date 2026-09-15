package com.kista.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// trading-core 전용 부팅 진입점. scanBasePackages는 root(com.kista.user/finance/admin/stats(벤치마크)/market/web)
// 패키지를 명시적으로 배제하기 위해 trading-core가 실제 소유한 최상위 패키지만 나열한다.
// @EnableJpaRepositories는 이 클래스가 아니라 별도 JpaRepositoryConfig(package-private)에 선언한다 —
// 이 클래스에 직접 붙이면 @WebMvcTest(@ContextConfiguration(classes = TradingApplication.class))가
// 슬라이스 테스트의 auto-configuration 제외 메커니즘을 우회해 entityManagerFactory 없는 슬라이스
// 컨텍스트에서도 리포지토리 프록시 생성을 시도해 전체 웹 슬라이스 테스트가 깨진다(실측 확인) —
// 별도 @Configuration 클래스로 빼면 @WebMvcTest의 TypeExcludeFilter가 컨트롤러 관련 빈이 아닌
// 이 클래스를 걸러내 슬라이스에선 무시되고, 실제 부트(TradingApplication의 scanBasePackages
// 컴포넌트 스캔)에서만 정상 활성화된다
@SpringBootApplication(scanBasePackages = {
        "com.kista.trading",
        "com.kista.matching",
        "com.kista.broker",
        "com.kista.account",
        "com.kista.privacy",
        "com.kista.marketcalendar",
        "com.kista.sharedkernel",
        "com.kista.platform",
})
@EntityScan(basePackages = {
        "com.kista.trading",
        "com.kista.broker",
        "com.kista.account",
        "com.kista.privacy",
        "com.kista.marketcalendar",
})
@ConfigurationPropertiesScan(basePackages = {
        "com.kista.trading",
        "com.kista.broker",
        "com.kista.platform",
})
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
