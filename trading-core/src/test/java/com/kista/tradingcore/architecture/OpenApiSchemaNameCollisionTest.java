package com.kista.tradingcore.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// springdoc은 스키마 이름을 simple name(또는 @Schema.name)으로 정하므로 같은 이름·다른 구조면 하나로 덮인다
@DisplayName("OpenAPI 스키마 이름 충돌 가드")
class OpenApiSchemaNameCollisionTest {

    @Test
    @DisplayName("web DTO의 유효 스키마 이름이 같으면 구성 필드도 같아야 한다")
    void sameSchemaNameMustHaveSameComponents() {
        Map<String, Class<?>> seen = new HashMap<>();
        List<String> collisions = new ArrayList<>();
        // 응답에 실리는 contract record도 스캔 대상에 포함한다
        for (JavaClass jc : new ClassFileImporter().importPackages("com.kista.tradingcore", "com.kista.shared.contract")) {
            String pkg = jc.getPackageName();
            if (!pkg.contains(".adapter.in.web") && !pkg.contains(".web.") && !pkg.endsWith(".dto") && !pkg.startsWith("com.kista.shared.contract")) continue;
            Class<?> c = jc.reflect();
            String name = effectiveName(c);
            Class<?> other = seen.putIfAbsent(name, c);
            if (other != null && !components(other).equals(components(c))) {
                collisions.add(name + ": " + other.getName() + " vs " + c.getName());
            }
        }
        assertThat(collisions).as("스키마 이름 충돌 — @Schema(name=...)으로 고유 이름을 준다").isEmpty();
    }

    private static String effectiveName(Class<?> c) {
        Schema s = c.getAnnotation(Schema.class);
        return s != null && !s.name().isEmpty() ? s.name() : c.getSimpleName();
    }

    // 패키지를 떼고 단순 이름으로 비교한다 (구조가 같은 중복 DTO는 허용)
    private static String simple(String typeName) {
        return typeName.replaceAll("[\\w.]+\\.", "");
    }

    // record는 컴포넌트, 그 외는 non-static 선언 필드 이름 목록
    private static List<String> components(Class<?> c) {
        if (c.isRecord()) return Arrays.stream(c.getRecordComponents()).map(rc -> rc.getName() + ":" + simple(rc.getGenericType().getTypeName())).toList();
        return Arrays.stream(c.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())).map(fl -> fl.getName() + ":" + simple(fl.getGenericType().getTypeName())).toList();
    }
}
