package com.kista.tradingcore.architecture;

import com.kista.tradingcore.TradingApplication;
import com.tngtech.archunit.ArchConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("kista-trading 프로세스 Spring Modulith 모듈 경계 규칙")
class ModulithArchitectureTest {

    @Test
    @DisplayName("모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        // additionalPackages로 붙은 shared 루트는 모듈이 전부 OPEN이라 Modulith 순환 규칙이 검사할 슬라이스가 0개다 —
        // failOnEmptyShould=true(archunit.properties)가 이를 실패로 만들므로 이 호출에만 스레드 로컬로 완화한다
        var modules = ArchConfiguration.withThreadLocalScope(config -> {
            config.setProperty("archRule.failOnEmptyShould", "false");
            return ApplicationModules.of(TradingApplication.class).verify();
        });

        // 모듈이 조용히 빠지거나 합쳐지는 것을 막는 기준선 — 위 완화의 공허 통과도 이 단언이 막는다
        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("account", "broker", "marketcalendar", "matching", "privacy", "trading",
                        "stats", "notify", "web", "kernel", "contract", "platform");
    }
}
