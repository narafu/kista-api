package com.kista.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Spring Modulith 모듈 경계 규칙")
class ModulithArchitectureTest {

    @Test
    @DisplayName("모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        // 패키지 이동 중에도 모델 루트가 흔들리지 않도록 앱 클래스가 아닌 루트 패키지로 고정 — 프로세스별 모델 전환은 패키지 이동 완료 후
        var modules = ApplicationModules.of("com.kista").verify();

        // 모듈이 조용히 빠지거나 합쳐지는 것을 막는 기준선(19개)
        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("account", "admin", "benchmark", "broker", "contract", "finance", "market",
                        "marketcalendar", "matching", "notify", "platform", "privacy", "sharedkernel", "trading",
                        "tradingnotify", "tradingstats", "tradingweb", "user", "web");

        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
