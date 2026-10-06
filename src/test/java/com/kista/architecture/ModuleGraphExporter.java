package com.kista.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// Modulith 모듈 그래프를 클릭 탐색용 단일 HTML(build/spring-modulith-docs/modules.html)로 내보낸다
final class ModuleGraphExporter {

    private static final Path OUTPUT = Path.of("build/spring-modulith-docs/modules.html"); // Documenter 출력 옆에 둔다
    private static final String TEMPLATE = "/architecture/module-graph.html"; // 데이터 자리표시자를 가진 템플릿
    private static final String PLACEHOLDER = "/*__DATA__*/null"; // 템플릿 안 JSON 삽입 지점
    private static final Set<String> EXCLUDED = Set.of("support"); // 테스트 픽스처(com.kista.support) — 운영 모듈 아님

    private ModuleGraphExporter() {
    }

    record Graph(List<Module> modules) {
    }

    // project: :api / :trading-core / :shared, deps: 이 모듈이 다른 모듈을 참조하는 클래스 단위 목록
    record Module(String name, String project, List<String> namedInterfaces,
                  List<String> listenedEvents, List<Dependency> deps) {
    }

    // Comparable — TreeSet으로 중복 제거 + 결정적 순서
    record Dependency(String target, String type, String source, String targetType) implements Comparable<Dependency> {
        private static final Comparator<Dependency> ORDER = Comparator.comparing(Dependency::target)
                .thenComparing(Dependency::type).thenComparing(Dependency::source).thenComparing(Dependency::targetType);

        @Override
        public int compareTo(Dependency other) {
            return ORDER.compare(this, other);
        }
    }

    static void write(ApplicationModules modules) {
        var graph = new Graph(modules.stream()
                .filter(m -> !EXCLUDED.contains(name(m)))
                .sorted(Comparator.comparing(ModuleGraphExporter::name))
                .map(m -> toModule(m, modules))
                .toList());

        // </script> 조기 종료 방지 — JSON 안의 "</"를 이스케이프
        var json = JsonMapper.builder().build().writeValueAsString(graph).replace("</", "<\\/");
        try (InputStream in = ModuleGraphExporter.class.getResourceAsStream(TEMPLATE)) {
            var template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Files.createDirectories(OUTPUT.getParent());
            Files.writeString(OUTPUT, template.replace(PLACEHOLDER, json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Module toModule(ApplicationModule module, ApplicationModules modules) {
        // 클래스 단위 의존 수집 — 제외 모듈로 향하는 의존은 버린다
        var deps = module.getDirectDependencies(modules).stream()
                .filter(d -> !EXCLUDED.contains(name(d.getTargetModule())))
                .map(d -> new Dependency(name(d.getTargetModule()), d.getDependencyType().name(),
                        d.getSourceType().getSimpleName(), d.getTargetType().getSimpleName()))
                .collect(Collectors.toCollection(TreeSet::new));

        return new Module(
                name(module),
                project(module),
                module.getNamedInterfaces().stream().filter(NamedInterface::isNamed).map(NamedInterface::getName).sorted().toList(),
                module.getEventsListenedTo(modules).stream().map(JavaClass::getSimpleName).sorted().distinct().toList(),
                List.copyOf(deps));
    }

    private static String name(ApplicationModule module) {
        return module.getIdentifier().toString();
    }

    // 클래스 산출물 경로로 Gradle 서브프로젝트 판별 (루트 테스트 classpath에 세 프로젝트가 함께 올라온다)
    private static String project(ApplicationModule module) {
        var uri = module.getBasePackage().stream().findFirst()
                .flatMap(JavaClass::getSource)
                .map(s -> s.getUri().toString().replace('\\', '/'))
                .orElse("");
        if (uri.contains("/trading-core/")) {
            return ":trading-core";
        }
        if (uri.contains("/shared/")) {
            return ":shared";
        }
        return ":api";
    }
}
