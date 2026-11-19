package com.kista.api.architecture;

import com.kista.api.ApiApplication;
import com.tngtech.archunit.ArchConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Spring Modulith 모듈 경계 규칙")
class ModulithArchitectureTest {

    @Test
    @DisplayName("한 애플리케이션 모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        // 모델 하나 — root·매매 엔진(tradingcore)·shared 모듈을 한 애플리케이션으로 검증한다
        // additionalPackages로 붙은 shared 루트는 모듈이 전부 OPEN이라 Modulith 순환 규칙이 검사할 슬라이스가 0개다 —
        // failOnEmptyShould=true(archunit.properties)가 이를 실패로 만들므로 이 호출에만 스레드 로컬로 완화한다.
        // 공허 통과는 바로 아래 모듈 ID 집합 단언이 막는다
        var modules = ArchConfiguration.withThreadLocalScope(config -> {
            config.setProperty("archRule.failOnEmptyShould", "false");
            return ApplicationModules.of(ApiApplication.class).verify();
        });

        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("admin", "benchmark", "finance", "market", "notify", "user", "web",
                        "account", "broker", "marketcalendar", "matching", "privacy", "trading", "stats",
                        "trading-notify", "trading-web",
                        "kernel", "contract", "platform");

        // 다이어그램 이름은 모듈 ID로 — 기본값(패키지 끝 이름)은 api.web·tradingcore.web이 둘 다 "Web"이라 충돌한다
        var diagram = Documenter.DiagramOptions.defaults().withDefaultDisplayName(m -> m.getIdentifier().toString());
        new Documenter(modules)
                .writeModulesAsPlantUml(diagram)
                .writeIndividualModulesAsPlantUml(diagram);
    }
}
