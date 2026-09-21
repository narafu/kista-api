package com.kista.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static org.assertj.core.api.Assertions.assertThat;

class GradleModuleBoundaryTest {

    // trading-core 서브프로젝트 소스만 스캔해, api 전용 모듈(user/admin/stats/finance/notify/market/web)을
    // 컴파일 타임에 참조하지 않는지 고정한다. 이 테스트가 실패하면 :api → :trading-core 단방향이 깨진 것.
    @Test
    void tradingCoreMustNotDependOnApiOnlyModules() {
        var importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of("trading-core/build/classes/java/main"));

        // 디렉토리가 비어있으면(:trading-core:compileJava 미실행 등) noClasses()가 공허하게 통과해버리는 걸 방지
        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.kista..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kista.user..", "com.kista.admin..", "com.kista.stats..",
                        "com.kista.finance..", "com.kista.notify..", "com.kista.market..",
                        "com.kista.web..")
                .check(importedClasses);
    }

    // trading-core 테스트/testFixtures 클래스도 api 전용 모듈을 참조하지 않는지 고정한다 —
    // 루트가 trading-core testFixtures를 소비하므로 반대 방향 참조가 생기면 테스트 클래스패스 순환이 된다.
    @Test
    void tradingCoreTestsMustNotDependOnApiOnlyModules() {
        var importedClasses = new ClassFileImporter()
                .importPaths(Path.of("trading-core/build/classes/java/test"),
                        Path.of("trading-core/build/classes/java/testFixtures"));

        // 디렉토리가 비어있으면(:trading-core:compileTestJava 미실행 등) noClasses()가 공허하게 통과해버리는 걸 방지
        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.kista..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kista.user..", "com.kista.admin..", "com.kista.stats..",
                        "com.kista.finance..", "com.kista.notify..", "com.kista.market..",
                        "com.kista.web..")
                .check(importedClasses);
    }

    // :shared(sharedkernel+platform) 서브프로젝트 소스만 스캔해, :trading-core·:api 어느 쪽도
    // 역참조하지 않는지 고정한다 — 3-서브프로젝트 그래프는 shared ← trading-core ← api,
    // shared ← api 단방향만 허용(shared가 leaf). 이 테스트가 실패하면 :shared→:trading-core
    // 또는 :shared→:api 역방향 의존이 생긴 것.
    @Test
    void sharedMustNotDependOnTradingCoreOrApiModules() {
        var importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of("shared/build/classes/java/main"));

        // 디렉토리가 비어있으면(:shared:compileJava 미실행 등) noClasses()가 공허하게 통과해버리는 걸 방지
        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.kista..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.sharedkernel.."))
                                .and(resideOutsideOfPackage("com.kista.platform..")))
                .check(importedClasses);
    }

    // 루트(:api) main에서 adapter.in.schedule 패키지는 scheduler role 전용 — kista-api role에는 빈이 없다.
    // .github/scripts/detect-deploy-scope.sh 가 이 패키지 변경만 있는 커밋을 deploy-scheduler 단독으로 분류하는 근거라,
    // 패키지 밖(AdminSchedulerController 제외)이 이 패키지 타입을 참조하면 kista-api가 배포 없이 낡은 코드를 실행하게 된다.
    // 게이트(@ConditionalOnProperty) 누락은 SchedulerDisabledContextTest가 컨텍스트 기준으로 잡는다.
    @Test
    void schedulerRoleOnlyPackageMustNotBeReferencedFromOutside() {
        var importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of("build/classes/java/main"));

        // 디렉토리가 비어있으면(:compileJava 미실행 등) noClasses()가 공허하게 통과해버리는 걸 방지
        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("..adapter.in.schedule..")
                .and().doNotHaveFullyQualifiedName("com.kista.web.AdminSchedulerController")
                .should().dependOnClassesThat().resideInAPackage("..adapter.in.schedule..")
                .check(importedClasses);
    }
}
