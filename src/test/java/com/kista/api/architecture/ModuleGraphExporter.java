package com.kista.api.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

// Modulith 모듈 그래프를 아키텍처 맵 데이터(DATA.modules)로 조립한다
final class ModuleGraphExporter {

    private ModuleGraphExporter() {
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

    // 프로세스별 모델을 합친다 — 모듈 키는 com.kista 아래 두 세그먼트(api.notify / tradingcore.notify)라 동명 모듈이 충돌하지 않고,
    // 두 모델에 모두 있는 shared 모듈은 한 번만 남긴다
    static List<Module> graph(ApplicationModules... models) {
        var merged = new TreeMap<String, Module>();
        for (var modules : models) {
            modules.forEach(m -> merged.putIfAbsent(name(m), toModule(m, modules)));
        }
        return List.copyOf(merged.values());
    }

    private static Module toModule(ApplicationModule module, ApplicationModules modules) {
        // 클래스 단위 의존 수집
        var deps = module.getDirectDependencies(modules).stream()
                .map(d -> new Dependency(name(d.getTargetModule()), d.getDependencyType().name(),
                        d.getSourceType().getSimpleName(), d.getTargetType().getSimpleName()))
                .collect(Collectors.toCollection(TreeSet::new));

        var name = name(module);
        return new Module(
                name,
                project(name),
                module.getNamedInterfaces().stream().filter(NamedInterface::isNamed).map(NamedInterface::getName).sorted().toList(),
                module.getEventsListenedTo(modules).stream().map(JavaClass::getSimpleName).sorted().distinct().toList(),
                List.copyOf(deps));
    }

    // com.kista.<루트>.<모듈> → "<루트>.<모듈>" — ArchitectureMapExporter.moduleOf와 같은 규칙
    private static String name(ApplicationModule module) {
        return module.getBasePackage().getName().replaceFirst("^com\\.kista\\.", "");
    }

    // 모듈 키의 루트 세그먼트로 Gradle 서브프로젝트 판별
    private static String project(String name) {
        return switch (name.split("\\.", 2)[0]) {
            case "tradingcore" -> ":trading-core";
            case "shared" -> ":shared";
            default -> ":api";
        };
    }
}
