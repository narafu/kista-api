package com.kista.api.architecture;

import com.kista.shared.platform.role.ConditionalOnWorkerRole;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// Caddy 라우팅(deploy/server/caddy/kista-api.caddy)의 @trading·@scheduler regex가 RoleRoutingGuard의 엔진 분류와 일치하는지 고정한다.
// 엔진 경로(핸들러가 com.kista.tradingcore 패키지이거나 @ConditionalOnWorkerRole)는 kista-worker로, 그 외 /api/**는 kista-web으로 가야 한다.
// 어긋나면 web이 엔진 요청을 503으로 거절하고(AccountBudgetLock은 JVM 로컬이라 엔진 요청은 worker에만 가야 한다), 라우팅이 컨트롤러를 못 따라가 404가 난
// 사고(2026-09-16 2단 경로, 2026-09-17 fida-orders)도 같은 검사로 막는다
class CaddyRoutingTest {

    private static final Path CADDY_SNIPPET = Path.of("deploy/server/caddy/kista-api.caddy");
    // 엔진 분류 기준 — RoleRoutingGuard.ENGINE_PACKAGE와 같다
    private static final String ENGINE_PACKAGE = "com.kista.tradingcore.";
    // 외부(FIDA)가 공인 도메인으로 호출하는 엔진 internal 경로 — 나머지 /api/internal/**은 kista-web이 내부망으로 worker를 직접 호출해 Caddy를 거치지 않는다
    private static final Set<String> PUBLIC_ENGINE_INTERNAL_PATHS = Set.of("/api/internal/fida-orders");

    private static final Pattern TRADING_ROUTE = loadRoute("@trading");
    private static final Pattern SCHEDULER_ROUTE = loadRoute("@scheduler");

    @Test
    void enginePathsAreRoutedToWorker() {
        var engine = controllerPaths(true);

        // 디렉토리가 비어있으면(compileJava 미실행 등) 공허하게 통과하는 걸 방지
        assertThat(engine).isNotEmpty().containsAll(PUBLIC_ENGINE_INTERNAL_PATHS);
        var misrouted = engine.stream()
                .filter(p -> !p.startsWith("/api/internal/") || PUBLIC_ENGINE_INTERNAL_PATHS.contains(p))
                .filter(p -> !(SCHEDULER_ROUTE.matcher(p).matches() || TRADING_ROUTE.matcher(p).matches()))
                .toList();
        assertThat(misrouted).as("엔진(worker) 경로인데 @trading/@scheduler regex에 안 걸려 kista-web으로 새는 경로 — " + CADDY_SNIPPET + " 갱신 필요")
                .isEmpty();
    }

    @Test
    void nonEnginePathsAreNotRoutedToWorker() {
        var web = controllerPaths(false);

        assertThat(web).isNotEmpty();
        var captured = web.stream()
                .filter(p -> TRADING_ROUTE.matcher(p).matches() || SCHEDULER_ROUTE.matcher(p).matches())
                .toList();
        assertThat(captured).as("kista-web 경로인데 @trading/@scheduler regex에 걸려 worker로 가는 경로 — " + CADDY_SNIPPET + " regex 축소 필요")
                .isEmpty();
    }

    // 스케쥴러 수동 트리거는 깊이와 무관하게 kista-worker로 가야 한다 — `/**` path 매처가 2단 경로(kbland-price-index/full-refresh)를 놓치던 결함 재발 방지
    @Test
    void schedulerPathsAreRoutedToWorker() {
        var schedulerPaths = controllerPaths(true).stream()
                .filter(p -> p.startsWith("/api/admin/scheduler/"))
                .toList();

        assertThat(schedulerPaths).isNotEmpty();
        assertThat(schedulerPaths).allMatch(p -> SCHEDULER_ROUTE.matcher(p).matches(),
                "@scheduler regex에 매치 — " + CADDY_SNIPPET);
    }

    // 스니펫에서 named matcher의 path_regexp 값 추출 — Go RE2와 Java regex는 이 패턴 범위(그룹·선택·수량자)에서 동일하게 동작
    private static Pattern loadRoute(String matcherName) {
        var prefix = matcherName + " path_regexp ";
        try {
            var line = Files.readAllLines(CADDY_SNIPPET).stream()
                    .map(String::strip)
                    .filter(l -> l.startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(CADDY_SNIPPET + "에 " + prefix + "매처가 없음"));
            return Pattern.compile(line.substring(prefix.length()).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 컨트롤러 경로(클래스 @RequestMapping × 메서드 매핑) 수집 — engine=true면 엔진 컨트롤러, false면 그 외(RoleRoutingGuard 분류와 동일)
    private static Set<String> controllerPaths(boolean engine) {
        return Stream.of("build/classes/java/main", "trading-core/build/classes/java/main")
                .flatMap(dir -> new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPath(Path.of(dir)).stream())
                .filter(c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class))
                .map(JavaClass::reflect)
                .filter(c -> isEngine(c) == engine)
                .flatMap(CaddyRoutingTest::paths)
                .filter(p -> p.startsWith("/api/"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean isEngine(Class<?> controller) {
        return controller.getName().startsWith(ENGINE_PACKAGE)
                || AnnotatedElementUtils.hasAnnotation(controller, ConditionalOnWorkerRole.class);
    }

    private static Stream<String> paths(Class<?> controller) {
        var bases = mappingPaths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        return Arrays.stream(controller.getDeclaredMethods())
                .map(m -> AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class))
                .filter(Objects::nonNull)
                .flatMap(m -> bases.stream().flatMap(b -> mappingPaths(m).stream().map(p -> normalize(b + "/" + p))));
    }

    // 경로 미지정 매핑(@GetMapping 단독 등)은 상위 경로 그대로
    private static List<String> mappingPaths(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
    }

    // 경로 변수({id})를 실제 세그먼트로 치환하고 중복·끝 슬래시 정리
    private static String normalize(String path) {
        var p = ("/" + path).replaceAll("\\{[^}]+}", "x").replaceAll("/+", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
