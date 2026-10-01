package com.kista.architecture;

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

// Caddy 라우팅(deploy/server/caddy/kista-api.caddy)의 @trading·@scheduler regex가 실제 컨트롤러 경로와 맞는지 고정한다.
// 프로세스가 갈린 뒤 라우팅이 컨트롤러를 못 따라가 404가 난 사고(2026-09-16 2단 경로, 2026-09-17 fida-orders) 재발 방지
class CaddyRoutingTest {

    private static final Path CADDY_SNIPPET = Path.of("deploy/server/caddy/kista-api.caddy");
    // 외부(FIDA)가 공인 도메인으로 호출하는 trading-core internal 경로 — 나머지 /api/internal/**은 kista-api가 내부망으로 직접 호출해 Caddy를 거치지 않는다
    private static final Set<String> PUBLIC_TRADING_INTERNAL_PATHS = Set.of("/api/internal/fida-orders");
    // @trading보다 먼저 kista-scheduler로 라우팅되는 prefix
    private static final String SCHEDULER_PREFIX = "/api/admin/scheduler/";

    private static final Pattern TRADING_ROUTE = loadRoute("@trading");
    private static final Pattern SCHEDULER_ROUTE = loadRoute("@scheduler");

    @Test
    void tradingCorePublicPathsAreRoutedToKistaTrading() {
        var paths = controllerPaths("trading-core/build/classes/java/main");

        // 디렉토리가 비어있으면(:trading-core:compileJava 미실행 등) 공허하게 통과하는 걸 방지
        assertThat(paths).isNotEmpty().containsAll(PUBLIC_TRADING_INTERNAL_PATHS);
        var misrouted = paths.stream()
                .filter(p -> !p.startsWith("/api/internal/") || PUBLIC_TRADING_INTERNAL_PATHS.contains(p))
                .filter(p -> SCHEDULER_ROUTE.matcher(p).matches() || !TRADING_ROUTE.matcher(p).matches())
                .toList();
        assertThat(misrouted).as("kista-trading 공개 경로인데 @trading regex에 안 걸려 kista-api로 새는 경로 — " + CADDY_SNIPPET + " 갱신 필요")
                .isEmpty();
    }

    @Test
    void rootPathsAreNotCapturedByTradingRoute() {
        var paths = controllerPaths("build/classes/java/main");

        assertThat(paths).isNotEmpty();
        var captured = paths.stream()
                .filter(p -> !p.startsWith(SCHEDULER_PREFIX))
                .filter(p -> TRADING_ROUTE.matcher(p).matches() || SCHEDULER_ROUTE.matcher(p).matches())
                .toList();
        assertThat(captured).as("kista-api 경로인데 @trading/@scheduler regex에 걸려 다른 컨테이너로 가는 경로 — " + CADDY_SNIPPET + " regex 축소 필요")
                .isEmpty();
    }

    // 스케쥴러 수동 트리거는 깊이와 무관하게 kista-scheduler로 가야 한다 — `/**` path 매처가 2단 경로(kbland-price-index/full-refresh)를
    // 놓쳐 kista-api(스케쥴러 빈 없음)로 새던 결함 재발 방지
    @Test
    void schedulerPathsAreRoutedToKistaScheduler() {
        var schedulerPaths = controllerPaths("build/classes/java/main").stream()
                .filter(p -> p.startsWith(SCHEDULER_PREFIX))
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

    // 컴파일 산출물의 모든 컨트롤러 경로(클래스 @RequestMapping × 메서드 매핑) 수집
    private static Set<String> controllerPaths(String classesDir) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of(classesDir)).stream()
                .filter(c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class))
                .map(JavaClass::reflect)
                .flatMap(CaddyRoutingTest::paths)
                .collect(Collectors.toCollection(TreeSet::new));
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
