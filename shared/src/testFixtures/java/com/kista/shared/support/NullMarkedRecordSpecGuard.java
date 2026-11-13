package com.kista.shared.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// @NullMarked record ↔ OpenAPI 스키마(required·nullable) 일치 검증 — root·trading-core 가드 테스트가 공유한다.
// 기대값은 컨버터(NullMarkedRecordModelConverter)와 독립적으로 계산한다(같은 헬퍼를 쓰면 헬퍼 버그가 양쪽을 통과)
public final class NullMarkedRecordSpecGuard {

    // 검증 결과 — checked: 스키마가 존재해 검증한 record 수, violations: 불일치 설명
    public record Report(int checked, List<String> violations) {
    }

    private NullMarkedRecordSpecGuard() {
    }

    // 실행 중 서버의 /api-docs를 받아 dumpTo에 저장하고 본문을 돌려준다(스냅샷 비교용)
    public static String fetchSpec(int port, Path dumpTo) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("/api-docs 응답 " + response.statusCode());
        }
        Files.createDirectories(dumpTo.getParent());
        Files.writeString(dumpTo, response.body());
        return response.body();
    }

    // packages 아래 main 소스의 @NullMarked 범위 record를 스펙 components.schemas와 대조한다
    public static Report verify(String specJson, String... packages) {
        JsonNode schemas = JsonMapper.builder().build().readTree(specJson).path("components").path("schemas");
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<Class<?>>> entry : recordsBySchemaName(packages).entrySet()) {
            JsonNode schema = schemas.path(entry.getKey());
            List<Class<?>> nullMarked = entry.getValue().stream().filter(NullMarkedRecordSpecGuard::isNullMarked).toList();
            if (schema.isMissingNode()) {
                continue; // 응답·요청에 쓰이지 않는 record
            }
            if (nullMarked.isEmpty()) {
                // 스펙에 나오는 web 노출 record가 @NullMarked 밖이면 null성이 스펙에 반영되지 않는다 — 조용히 넘기지 않는다
                List<Class<?>> unmarked = entry.getValue().stream().filter(NullMarkedRecordSpecGuard::isWebFacing).toList();
                if (!unmarked.isEmpty()) {
                    violations.add(entry.getKey() + ": 스펙에 노출되는데 @NullMarked 범위 밖 " + unmarked);
                }
                continue;
            }
            // 스키마명 충돌(@NullMarked 밖 web 노출 record 포함) — springdoc은 이름당 스키마 1개만 남겨 어느 record의 스키마인지 판정 불가.
            // 단 @NullMarked record끼리 기대 계약(required 집합·nullable 프로퍼티 집합)이 같으면 어느 쪽이 남아도 와이어가 같아 한 번만 검사한다
            List<Class<?>> exposed = entry.getValue().stream().filter(c -> isNullMarked(c) || isWebFacing(c)).toList();
            if (exposed.size() > 1 && !(nullMarked.size() == exposed.size() && sameContract(nullMarked))) {
                violations.add(entry.getKey() + ": 스키마명 충돌 " + exposed);
                continue;
            }
            checked++;
            violations.addAll(compare(entry.getKey(), nullMarked.getFirst(), schema));
        }
        return new Report(checked, violations);
    }

    // 모든 record의 기대 계약(required·nullable JSON 이름 집합)이 같은지
    private static boolean sameContract(List<Class<?>> types) {
        return types.stream().map(NullMarkedRecordSpecGuard::contract).distinct().count() == 1;
    }

    // 기대 계약 — compare()와 같은 기준: JSON 이름 → null 허용 여부(스키마 properties 필터는 없이 컴포넌트 기준)
    private static Map<String, Boolean> contract(Class<?> type) {
        Map<String, Boolean> contract = new TreeMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            String json = jsonName(type, component);
            if (json != null) {
                contract.put(json, isNullable(component));
            }
        }
        return contract;
    }

    private static boolean isNullable(RecordComponent component) {
        return !component.getType().isPrimitive() && component.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }

    // 스키마명(@Schema(name) 우선, 없으면 단순명) → 그 이름의 record 목록(main 소스 전체 — 충돌 판정용)
    private static Map<String, List<Class<?>>> recordsBySchemaName(String... packages) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packages)
                .stream()
                .filter(JavaClass::isRecord)
                .map(JavaClass::reflect)
                .collect(Collectors.groupingBy(NullMarkedRecordSpecGuard::schemaName, TreeMap::new, Collectors.toList()));
    }

    // 스펙에 실릴 수 있는 record — web 인바운드 어댑터 DTO, 앱셸 web 패키지, shared wire 계약(도메인·아웃바운드 벤더 record는 응답에 직접 쓰지 않는다)
    private static boolean isWebFacing(Class<?> type) {
        String pkg = type.getPackageName() + ".";
        return pkg.contains(".adapter.in.web.") || pkg.startsWith("com.kista.shared.contract.")
                || pkg.startsWith("com.kista.api.web.") || pkg.startsWith("com.kista.tradingcore.web.");
    }

    // 클래스 자신·enclosing 클래스·패키지 중 하나에 @NullMarked가 있으면 범위 안
    private static boolean isNullMarked(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (c.isAnnotationPresent(NullMarked.class)) {
                return true;
            }
        }
        return type.getPackage().isAnnotationPresent(NullMarked.class);
    }

    private static String schemaName(Class<?> type) {
        Schema schema = type.getAnnotation(Schema.class);
        return schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
    }

    // required 집합 == non-null 컴포넌트 JSON 이름 집합, @Nullable 프로퍼티는 null 허용
    private static List<String> compare(String name, Class<?> type, JsonNode schema) {
        JsonNode properties = schema.path("properties");
        Set<String> expectedRequired = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            String json = jsonName(type, component);
            if (json == null || isHidden(type, component)) {
                continue; // @JsonIgnore 또는 @Schema(hidden = true)
            }
            if (!properties.has(json)) {
                violations.add(name + "." + json + ": 직렬화되는 컴포넌트인데 스키마 properties에 없음");
                continue;
            }
            boolean nullable = isNullable(component);
            if (!nullable) {
                expectedRequired.add(json);
            } else if (!allowsNull(properties.path(json))) {
                violations.add(name + "." + json + ": @Nullable인데 스키마가 null을 허용하지 않음");
            }
        }
        Set<String> actualRequired = new LinkedHashSet<>();
        schema.path("required").forEach(r -> actualRequired.add(r.asString()));
        if (!actualRequired.equals(expectedRequired)) {
            violations.add(name + ": required " + actualRequired + " != 기대 " + expectedRequired);
        }
        return violations;
    }

    // 컴포넌트에 @Schema(hidden = true) — 스펙에서 의도적으로 숨긴 프로퍼티
    private static boolean isHidden(Class<?> type, RecordComponent component) {
        return annotationSites(type, component).stream().map(site -> site.getAnnotation(Schema.class))
                .anyMatch(schema -> schema != null && schema.hidden());
    }

    // 레코드 컴포넌트 어노테이션은 accessor·필드로 전파된다
    private static List<AnnotatedElement> annotationSites(Class<?> type, RecordComponent component) {
        try {
            return List.of(component.getAccessor(), type.getDeclaredField(component.getName()));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    // @JsonIgnore면 null, @JsonProperty 값이 있으면 그 값
    private static @Nullable String jsonName(Class<?> type, RecordComponent component) {
        String name = component.getName();
        for (AnnotatedElement site : annotationSites(type, component)) {
            JsonIgnore ignore = site.getAnnotation(JsonIgnore.class);
            if (ignore != null && ignore.value()) {
                return null;
            }
            JsonProperty property = site.getAnnotation(JsonProperty.class);
            if (property != null && !property.value().isEmpty()) {
                name = property.value();
            }
        }
        return name;
    }

    // OAS 3.1 — type 배열에 "null"(enum이 있으면 enum에도 null) 또는 oneOf에 {type: null} 분기
    private static boolean allowsNull(JsonNode property) {
        if (typeAllowsNull(property.path("type"))) {
            return enumAllowsNull(property.path("enum"));
        }
        for (JsonNode branch : property.path("oneOf")) {
            if (allowsNull(branch)) {
                return true;
            }
        }
        return false;
    }

    private static boolean typeAllowsNull(JsonNode type) {
        if (type.isString()) {
            return "null".equals(type.asString());
        }
        for (JsonNode t : type) {
            if (t.isString() && "null".equals(t.asString())) {
                return true;
            }
        }
        return false;
    }

    // enum이 없거나 enum 목록에 null 원소가 있으면 null 값이 enum 검증을 통과한다
    private static boolean enumAllowsNull(JsonNode values) {
        if (values.isMissingNode()) {
            return true;
        }
        for (JsonNode v : values) {
            if (v.isNull()) {
                return true;
            }
        }
        return false;
    }
}
