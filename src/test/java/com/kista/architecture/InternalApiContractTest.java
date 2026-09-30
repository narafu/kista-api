package com.kista.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

// 내부 API(/api/internal/**) wire 계약 잠금 — Published Language(com.kista.contract) 규칙.
// 프로세스 경계(root :api ↔ :trading-core)를 넘는 body 타입은 contract·sharedkernel·JDK만 허용한다.
// 도메인 record(Order/Strategy 등)를 그대로 직렬화하거나 own-type을 손으로 복제하던 과거 방식(OwnTypeContractTest)을
// 컴파일 타임 공유 + 이 테스트로 대체한다 — 도메인 필드 추가가 wire 계약을 조용히 바꾸는 일을 막는다.
@DisplayName("내부 API wire 계약 규칙")
class InternalApiContractTest {

    private static final String INTERNAL_PREFIX = "/api/internal";
    private static final List<String> ALLOWED_PACKAGE_PREFIXES = List.of("java.", "com.kista.contract.", "com.kista.sharedkernel.");
    // wire 타입 자체가 아니라 감싸기만 하는 컨테이너 — 타입 인자를 재귀 검사한다
    private static final Set<Class<?>> WRAPPERS = Set.of(
            ResponseEntity.class, List.class, Set.class, Collection.class, Optional.class, Map.class);

    // 4단계(F5)에서 엔드포인트째 삭제될 예정인 임시 예외 — 상수 조회용 StrategyCapabilityResponse는 contract로 옮기지 않는다
    private static final Set<String> PENDING_REMOVAL_CONTROLLERS = Set.of(
            "com.kista.matching.adapter.in.web.StrategyCapabilityInternalController");

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().importPackages("com.kista");
    }

    @Test
    @DisplayName("내부 API 핸들러의 반환·@RequestBody 타입은 contract·sharedkernel·JDK 타입만 쓴다")
    void internal_api_handlers_must_only_use_contract_types() {
        List<String> violations = new ArrayList<>();
        int handlerCount = 0;
        for (JavaClass controller : classes) {
            if (!controller.isAnnotatedWith(RestController.class)) continue;
            if (PENDING_REMOVAL_CONTROLLERS.contains(controller.getName())) continue;
            String[] classPaths = classPaths(controller);
            for (JavaMethod method : controller.getMethods()) {
                Method reflected = method.reflect();
                List<String> methodPaths = handlerPaths(reflected);
                if (methodPaths == null || !isInternal(classPaths, methodPaths)) continue;
                handlerCount++;
                checkType(reflected.getGenericReturnType(), method.getFullName() + " 반환", violations);
                for (int i = 0; i < reflected.getParameterCount(); i++) {
                    if (Arrays.stream(reflected.getParameterAnnotations()[i]).anyMatch(a -> a instanceof RequestBody)) {
                        checkType(reflected.getGenericParameterTypes()[i], method.getFullName() + " @RequestBody", violations);
                    }
                }
            }
        }
        // 컨트롤러가 하나도 안 잡히면 규칙이 공허하게 통과한다 — trading-core는 root testImplementation으로 클래스패스에 있다
        assertThat(handlerCount).as("검사 대상 내부 API 핸들러 수").isGreaterThan(20);
        assertThat(violations).as("contract 밖 타입을 wire에 노출한 핸들러").isEmpty();
    }

    @Test
    @DisplayName("contract는 JDK·sharedkernel·Jackson/Swagger/Bean Validation 어노테이션 외에 의존하지 않는다")
    void contract_must_only_depend_on_allowed_packages() {
        classes().that().resideInAPackage("com.kista.contract..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "com.kista.contract..", "com.kista.sharedkernel..",
                        "com.fasterxml.jackson..", "tools.jackson..", "io.swagger..", "jakarta.validation..",
                        "org.springframework.modulith..") // package-info의 @ApplicationModule 선언
                .check(classes);
    }

    // 컨트롤러 클래스 레벨 @RequestMapping 경로 — 없으면 빈 접두사
    private static String[] classPaths(JavaClass controller) {
        return controller.tryGetAnnotationOfType(RequestMapping.class)
                .map(a -> a.value().length > 0 ? a.value() : a.path())
                .filter(p -> p.length > 0)
                .orElse(new String[]{""});
    }

    // 핸들러 메서드의 매핑 경로 — 매핑 어노테이션이 없으면 null(핸들러 아님), 경로 생략 시 빈 문자열
    private static List<String> handlerPaths(Method m) {
        String[] paths;
        if (m.isAnnotationPresent(GetMapping.class)) paths = merge(m.getAnnotation(GetMapping.class).value(), m.getAnnotation(GetMapping.class).path());
        else if (m.isAnnotationPresent(PostMapping.class)) paths = merge(m.getAnnotation(PostMapping.class).value(), m.getAnnotation(PostMapping.class).path());
        else if (m.isAnnotationPresent(PutMapping.class)) paths = merge(m.getAnnotation(PutMapping.class).value(), m.getAnnotation(PutMapping.class).path());
        else if (m.isAnnotationPresent(PatchMapping.class)) paths = merge(m.getAnnotation(PatchMapping.class).value(), m.getAnnotation(PatchMapping.class).path());
        else if (m.isAnnotationPresent(DeleteMapping.class)) paths = merge(m.getAnnotation(DeleteMapping.class).value(), m.getAnnotation(DeleteMapping.class).path());
        else if (m.isAnnotationPresent(RequestMapping.class)) paths = merge(m.getAnnotation(RequestMapping.class).value(), m.getAnnotation(RequestMapping.class).path());
        else return null;
        return paths.length == 0 ? List.of("") : List.of(paths);
    }

    private static String[] merge(String[] value, String[] path) {
        return Stream.concat(Arrays.stream(value), Arrays.stream(path)).toArray(String[]::new);
    }

    // 클래스 경로 × 메서드 경로 조합 중 하나라도 /api/internal로 시작하면 내부 API
    private static boolean isInternal(String[] classPaths, List<String> methodPaths) {
        return Arrays.stream(classPaths).anyMatch(c -> methodPaths.stream().anyMatch(m -> (c + m).startsWith(INTERNAL_PREFIX)));
    }

    private static void checkType(Type type, String where, List<String> violations) {
        if (type instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            if (WRAPPERS.contains(raw)) {
                for (Type arg : p.getActualTypeArguments()) checkType(arg, where, violations);
            } else {
                checkClass(raw, where, violations);
            }
        } else if (type instanceof Class<?> c) {
            checkClass(c, where, violations);
        } else if (type instanceof GenericArrayType g) {
            checkType(g.getGenericComponentType(), where, violations);
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) checkType(bound, where, violations);
        }
    }

    private static void checkClass(Class<?> c, String where, List<String> violations) {
        if (c.isArray()) {
            checkClass(c.getComponentType(), where, violations);
            return;
        }
        if (c.isPrimitive() || c == Void.class) return;
        if (ALLOWED_PACKAGE_PREFIXES.stream().noneMatch(c.getName()::startsWith)) {
            violations.add(where + ": " + c.getName());
        }
    }
}
