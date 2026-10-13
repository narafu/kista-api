package com.kista.api.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleModuleBoundaryTest {

    // trading-core 서브프로젝트 소스만 스캔해, api 전용 루트(com.kista.api 전체)를
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
                .should().dependOnClassesThat().resideInAPackage("com.kista.api..")
                .check(importedClasses);
    }

    // trading-core 테스트/testFixtures 클래스도 com.kista.api 전체를 참조하지 않는지 고정한다 —
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
                .should().dependOnClassesThat().resideInAPackage("com.kista.api..")
                .check(importedClasses);
    }

    // :shared(shared.kernel+platform+contract) 서브프로젝트 소스만 스캔해, :trading-core·:api 어느 쪽도
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
                .should().dependOnClassesThat().resideInAnyPackage("com.kista.api..", "com.kista.tradingcore..")
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
                .and().doNotHaveFullyQualifiedName("com.kista.api.web.AdminSchedulerController")
                .should().dependOnClassesThat().resideInAPackage("..adapter.in.schedule..")
                .check(importedClasses);
    }

    // trading-core는 root를 호출하지 않는다 — 프로세스 간 런타임 의존은 root→trading-core 단방향(HTTP)이고, 반대 방향은
    // Redis Stream push(stream:app.error)뿐이다. 과거 BrokerEnabledHttpAdapter/StrategyCreationPolicyHttpAdapter/
    // TradingExceptionHandler가 root 내부 API를 동기 호출하던 역방향 의존이 재발하지 않도록 내부 API 클라이언트 패키지
    // (platform.internalapi — InternalApiClientConfig/RestClient 빈) 참조 자체를 컴파일 산출물 기준으로 금지한다.
    @Test
    void tradingCoreMustNotCallRootInternalApi() {
        var importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of("trading-core/build/classes/java/main"));

        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.kista..")
                .should().dependOnClassesThat().resideInAPackage("com.kista.shared.platform.internalapi..")
                .check(importedClasses);
    }

    // 서브프로젝트마다 고유 루트 패키지를 갖는다 — 스캔 목록 없이 기본 스캔(자기 루트)만으로 프로세스가 분리되는 근거.
    // 다른 루트에 클래스를 두면 root 테스트 classpath의 trading-core 빈이 root 컨텍스트에 섞이는 경로가 다시 생긴다
    @ParameterizedTest
    @CsvSource({
            "build/classes/java/main, com.kista.api..",
            "build/classes/java/test, com.kista.api..",
            "build/classes/java/testFixtures, com.kista.api..",
            "trading-core/build/classes/java/main, com.kista.tradingcore..",
            "trading-core/build/classes/java/testFixtures, com.kista.tradingcore..",
            "shared/build/classes/java/main, com.kista.shared..",
            "shared/build/classes/java/test, com.kista.shared..",
            "shared/build/classes/java/testFixtures, com.kista.shared..",
    })
    void subprojectClassesStayUnderOwnRootPackage(String classesDir, String rootPackage) {
        var importedClasses = new ClassFileImporter().importPath(Path.of(classesDir));
        assertThat(importedClasses).isNotEmpty(); // 산출물 미생성 시 공허 통과 방지

        ArchRuleDefinition.classes().should().resideInAPackage(rootPackage).check(importedClasses);
    }

    // trading-core 테스트는 자기 루트 + SchedulerLockService DB 테스트(같은 패키지 접근 필요, scheduler_locks는
    // trading 마이그레이션이 만든다 — :shared 테스트로 옮기면 테이블이 없다)만 예외로 둔다
    @Test
    void tradingCoreTestClassesStayUnderOwnRootPackage() {
        var importedClasses = new ClassFileImporter().importPath(Path.of("trading-core/build/classes/java/test"));
        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.classes().should()
                .resideInAnyPackage("com.kista.tradingcore..", "com.kista.shared.platform.scheduling..")
                .check(importedClasses);
    }
}
