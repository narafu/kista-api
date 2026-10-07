package com.kista.architecture;

import com.kista.KistaApplication;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("아키텍처 맵(build/architecture-map)")
class ArchitectureMapTest {

    @Test
    @DisplayName("flows.yml이 실제 코드·레인·흐름만 참조하고, 아키텍처 맵을 생성한다")
    void flowsReferenceExistingCodeAndExport() {
        // 운영 코드만 — 테스트·testFixtures 클래스가 단순 이름 유일성 판정을 흐리지 않도록 제외
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("testFixtures") && !location.contains("test-fixtures"))
                .importPackages("com.kista");
        var map = ArchitectureMapExporter.load();

        assertThat(ArchitectureMapExporter.violations(map, classes)).isEmpty();

        // 프로세스 판별·KST 시각 계산 회귀 가드 — 마감 배치는 kista-trading에서 04:30
        var jobs = ArchitectureMapExporter.jobs(classes);
        assertThat(jobs).filteredOn(j -> j.name().equals("TradingCloseScheduler#run"))
                .singleElement()
                .satisfies(j -> {
                    assertThat(j.process()).isEqualTo("kista-trading");
                    assertThat(j.times()).containsExactly("04:30");
                });

        var modules = ModuleGraphExporter.graph(ApplicationModules.of(KistaApplication.class));
        // 단계 code: 클래스는 전부 모듈 소속이어야 하고, 오버레이 회귀 가드 — 마감 배치 락 단계는 trading을 거친다
        var moduleNames = modules.stream().map(ModuleGraphExporter.Module::name).collect(Collectors.toSet());
        assertThat(ArchitectureMapExporter.moduleViolations(map, classes, moduleNames)).isEmpty();
        var stepModules = ArchitectureMapExporter.stepModules(map, classes);
        assertThat(stepModules.get("close-batch").get("lock")).contains("trading");

        ArchitectureMapExporter.write(modules, map, stepModules, jobs);

        // 셸·생성 데이터·복사된 뷰가 모두 출력에 있어야 한다
        assertThat(ArchitectureMapExporter.OUTPUT.resolve("index.html")).exists();
        assertThat(ArchitectureMapExporter.OUTPUT.resolve("data.js")).content().startsWith("window.DATA = ");
        assertThat(ArchitectureMapExporter.OUTPUT.resolve("views/structure.js")).exists();
    }
}
