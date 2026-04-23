package com.kista.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@DisplayName("Hexagonal Architecture 규칙")
class HexagonalArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().importPackages("com.kista");
    }

    @Test
    @DisplayName("도메인은 어떤 외부 레이어도 의존하지 않는다")
    void domain_must_not_depend_on_outer_layers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.kista.application..",
                        "com.kista.adapter..",
                        "org.springframework.stereotype..",
                        "jakarta.persistence.."
                );
        rule.check(classes);
    }

    @Test
    @DisplayName("인바운드 어댑터는 application 레이어 구현체에 직접 의존하지 않는다")
    void inbound_adapters_must_not_depend_on_application_layer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista.application..");
        rule.check(classes);
    }

    @Test
    @DisplayName("application 레이어는 adapter 레이어를 의존하지 않는다")
    void application_must_not_depend_on_adapter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista.adapter..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Service 클래스는 @Service 어노테이션을 가져야 한다")
    void service_classes_must_be_annotated_with_service() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista.application.service..")
                .and().haveSimpleNameEndingWith("Service")
                .should().beAnnotatedWith(org.springframework.stereotype.Service.class);
        rule.check(classes);
    }
}
