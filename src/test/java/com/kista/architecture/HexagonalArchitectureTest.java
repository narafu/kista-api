package com.kista.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

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
                .and().resideOutsideOfPackage("com.kista.domain.strategy..")
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

    @Test
    @DisplayName("domain/port/out 인터페이스는 *Port 접미사를 가져야 한다")
    void outbound_port_interfaces_must_have_Port_suffix() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista.domain.port.out..")
                .and().areInterfaces()
                .should().haveSimpleNameEndingWith("Port");
        rule.check(classes);
    }

    @Test
    @DisplayName("persistence JpaRepository는 *JpaRepository 접미사를 가져야 한다")
    void persistence_jpa_repositories_must_have_JpaRepository_suffix() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista.adapter.out.persistence..")
                .and().areInterfaces()
                .and().areAssignableTo(org.springframework.data.jpa.repository.JpaRepository.class)
                .should().haveSimpleNameEndingWith("JpaRepository");
        rule.check(classes);
    }

    @Test
    @DisplayName("persistence JpaRepository는 package-private이어야 한다")
    void persistence_jpa_repositories_must_be_package_private() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.kista.adapter.out.persistence..")
                .and().areInterfaces()
                .and().haveSimpleNameEndingWith("JpaRepository")
                .should().bePackagePrivate();
        rule.check(classes);
    }

    @Test
    @DisplayName("application.service는 org.springframework.web에 의존하지 않는다")
    void application_service_must_not_depend_on_spring_web() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.application.service..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..");
        rule.check(classes);
    }

    @Test
    @DisplayName("application.service는 org.springframework.http.HttpStatus에 의존하지 않는다")
    void application_service_must_not_depend_on_http_status() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.application.service..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.http.HttpStatus");
        rule.check(classes);
    }

    @Test
    @DisplayName("KIS 파서는 application 레이어를 의존하지 않는다 — 정규화는 outbound 책임")
    void kis_parser_must_not_depend_on_application_layer() {
        // adapter.out은 domain.model 사용이 정상 — application 서비스 직접 의존만 금지
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.adapter.out.kis..")
                .and().haveSimpleNameEndingWith("Parser")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista.application..");
        rule.check(classes);
    }

    @Test
    @DisplayName("SSE EmitterRegistry는 adapter.in (controller)에서만 주입된다 — application 직접 의존 금지")
    void sse_emitter_registry_must_not_be_used_in_application_layer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.application..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista.adapter.out.sse..");
        rule.check(classes);
    }

    @Test
    @DisplayName("RestController는 domain port 인터페이스(UseCase/Port)에만 의존하고 application 구현체에 직접 의존하지 않는다")
    void rest_controllers_must_not_depend_on_application_implementations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.adapter.in.web..")
                .and().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().dependOnClassesThat()
                .resideInAPackage("com.kista.application.service..");
        rule.check(classes);
    }

    @Test
    @DisplayName("생성자가 2개 이상인 Spring 빈은 정확히 하나에 @Autowired가 있어야 한다")
    void multi_constructor_beans_must_have_exactly_one_autowired_constructor() {
        // 실 인시던트 재발 방지: TossRedisTokenStore가 생성자 2개(테스트용 Clock 주입 오버로드 포함)인데
        // @Autowired가 없어 Spring이 기본 생성자를 못 찾고 BeanCreationException으로 부팅 자체가 실패한 사례
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.springframework.stereotype.Component.class)
                .or().areAnnotatedWith(org.springframework.stereotype.Service.class)
                .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
                .should(haveExactlyOneAutowiredConstructorWhenMultiple());
        rule.check(classes);
    }

    private static ArchCondition<JavaClass> haveExactlyOneAutowiredConstructorWhenMultiple() {
        return new ArchCondition<>("생성자가 2개 이상이면 정확히 하나에 @Autowired가 있어야 함") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<JavaConstructor> constructors = javaClass.getConstructors();
                if (constructors.size() < 2) {
                    return;
                }
                long autowiredCount = constructors.stream()
                        .filter(constructor -> constructor.isAnnotatedWith(Autowired.class))
                        .count();
                if (autowiredCount != 1) {
                    events.add(SimpleConditionEvent.violated(javaClass, String.format(
                            "%s has %d constructors but %d are annotated with @Autowired (expected exactly 1)",
                            javaClass.getFullName(), constructors.size(), autowiredCount)));
                }
            }
        };
    }
}
