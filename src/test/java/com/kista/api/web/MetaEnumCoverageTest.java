package com.kista.api.web;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

// 가드: web DTO(record 컴포넌트, 중첩 record·List<E>·Map<E,…> 키 포함)가 참조하는 enum은 전부 /api/meta 번들에 실려야 한다.
// 새 enum이 응답·요청에 나가면 MetaEnumRegistry에 한 줄을 더한다.
class MetaEnumCoverageTest {

    // 내부 전용 enum 허용 목록 — 예외는 이유 주석과 함께 여기에 둔다
    // java.time.DayOfWeek는 JDK 타입이라 라벨(요일 표기)을 UI가 로케일로 만든다 — 서버 enum 코드값 계약이 아니다
    private static final Set<Class<?>> INTERNAL_ONLY = Set.of(java.time.DayOfWeek.class);

    @Test
    void web_dto_enums_must_be_carried_by_meta_bundle() {
        var imported = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.kista.api", "com.kista.tradingcore");
        Set<Class<?>> found = new HashSet<>();
        Set<Class<?>> visited = new HashSet<>();
        for (JavaClass c : imported) {
            String pkg = c.getPackageName();
            boolean webDto = pkg.contains(".adapter.in.web") || pkg.equals("com.kista.api.web.dto");
            if (webDto && c.isRecord()) {
                collect(c.reflect(), found, visited);
            }
        }

        // 스캔이 비면 가드가 무의미하다 — 알려진 enum 하나는 잡혀야 한다
        assertThat(found).contains(com.kista.shared.kernel.OrderStatus.class, com.kista.tradingcore.trading.domain.model.NextOrdersPreview.SkipReason.class);

        Set<Class<?>> missing = new HashSet<>(found);
        missing.removeAll(MetaEnumRegistry.carriedEnums());
        missing.removeAll(INTERNAL_ONLY);
        assertThat(names(missing)).as("메타 번들에 없는 web DTO enum — MetaEnumRegistry.SPECS에 등록하세요").isEmpty();
    }

    private static Set<String> names(Set<Class<?>> classes) {
        Set<String> names = new TreeSet<>();
        classes.forEach(c -> names.add(c.getName()));
        return names;
    }

    // record 컴포넌트 타입을 따라가며 enum을 모은다 (record는 재귀, 그 외 클래스는 멈춘다)
    private static void collect(Class<?> recordType, Set<Class<?>> found, Set<Class<?>> visited) {
        if (!visited.add(recordType)) return;
        for (RecordComponent component : recordType.getRecordComponents()) {
            collectType(component.getGenericType(), found, visited);
        }
    }

    private static void collectType(Type type, Set<Class<?>> found, Set<Class<?>> visited) {
        switch (type) {
            case Class<?> c when c.isEnum() -> found.add(c);
            case Class<?> c when c.isRecord() -> collect(c, found, visited);
            case Class<?> c when c.isArray() -> collectType(c.getComponentType(), found, visited);
            case ParameterizedType p -> {
                collectType(p.getRawType(), found, visited);
                for (Type arg : p.getActualTypeArguments()) collectType(arg, found, visited);
            }
            case GenericArrayType g -> collectType(g.getGenericComponentType(), found, visited);
            case WildcardType w -> {
                for (Type bound : w.getUpperBounds()) collectType(bound, found, visited);
            }
            default -> { }
        }
    }
}
