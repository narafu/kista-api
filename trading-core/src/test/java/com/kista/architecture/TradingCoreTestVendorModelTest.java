package com.kista.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// root HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker는 trading-core 테스트 클래스를 보지 못한다 —
// 이 테스트가 trading-core **테스트** 소스에도 같은 벤더 타입 금지를 적용한다(broker 모듈 테스트는 벤더 타입을 써도 된다).
class TradingCoreTestVendorModelTest {

    private static JavaClasses testClasses; // trading-core 테스트 산출물만 임포트

    @BeforeAll
    static void importTestClasses() {
        testClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages("com.kista");
    }

    @Test
    @DisplayName("broker 밖 trading-core 테스트도 벤더 전용 도메인 모델(domain.model.kis/toss)을 참조하지 않는다")
    void tests_outside_broker_must_not_use_vendor_models() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.kista.trading..", "com.kista.tradingweb..", "com.kista.tradingstats..",
                        "com.kista.account..", "com.kista.privacy..", "com.kista.marketcalendar..", "com.kista.matching..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.kista.broker.domain.model.kis..", "com.kista.broker.domain.model.toss..");
        rule.check(testClasses);
    }
}
