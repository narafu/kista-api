package com.kista.shared.platform.openapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// jspecify @NullMarked 범위 record의 스키마를 와이어 null성과 맞춘다 — swagger-core는 @NullMarked를 읽지 않고
// record 컴포넌트의 TYPE_USE @Nullable도 보지 못한다. non-@Nullable 컴포넌트 → required, @Nullable → null 허용(OAS 3.1).
// springdoc이 ModelConverter 빈을 전역 ModelConverters에 등록한다(ModelConverterRegistrar)
@ConditionalOnClass(name = "io.swagger.v3.core.converter.ModelConverter") // swagger-core는 compileOnly — springdoc 없는 classpath에선 빠진다
public class NullMarkedRecordModelConverter implements ModelConverter {

    private static final String REF_PREFIX = "#/components/schemas/"; // 컴포넌트 $ref 접두사

    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema resolved = chain.next().resolve(type, context, chain);
        Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
        if (resolved == null || !raw.isRecord() || !isNullMarked(raw)) {
            return resolved;
        }
        // $ref로 돌아오면 정의된 모델 본체를 찾아 고친다
        Schema model = resolved.get$ref() != null
                ? context.getDefinedModels().get(resolved.get$ref().substring(REF_PREFIX.length()))
                : resolved;
        if (model != null && model.getProperties() != null) {
            applyNullness(raw, model);
        }
        return resolved;
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

    // required를 non-null 컴포넌트로 덮어쓰고 @Nullable 프로퍼티에 null 분기를 단다(멱등)
    private static void applyNullness(Class<?> record, Schema<?> model) {
        Map<String, Schema> properties = model.getProperties();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            String name = jsonName(record, component);
            Schema property = name == null ? null : properties.get(name);
            if (property == null) {
                continue; // @JsonIgnore 또는 스키마에 없는 프로퍼티
            }
            boolean nullable = !component.getType().isPrimitive()
                    && component.getAnnotatedType().isAnnotationPresent(Nullable.class);
            if (nullable) {
                properties.put(name, withNull(property));
            } else {
                required.add(name);
            }
        }
        model.setRequired(required.isEmpty() ? null : required);
    }

    // @JsonIgnore면 null, @JsonProperty 값이 있으면 그 값 — 레코드 컴포넌트 어노테이션은 필드·accessor로 전파된다
    private static @Nullable String jsonName(Class<?> record, RecordComponent component) {
        String name = component.getName();
        for (AnnotatedElement site : annotationSites(record, component)) {
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

    private static List<AnnotatedElement> annotationSites(Class<?> record, RecordComponent component) {
        try {
            return List.of(component.getAccessor(), record.getDeclaredField(component.getName()));
        } catch (NoSuchFieldException e) {
            return List.of(component.getAccessor()); // record 컴포넌트는 항상 private 필드를 가진다 — 방어용
        }
    }

    // null을 허용하는 프로퍼티 스키마 — 컨텍스트가 같은 인스턴스를 여러 프로퍼티에 공유할 수 있어 원본은 건드리지 않는다
    private static Schema withNull(Schema property) {
        if (allowsNull(property)) {
            return property;
        }
        // $ref 단독 → oneOf [{$ref}, {type: null}] (OAS 3.1은 $ref 형제 nullable을 쓸 수 없다)
        if (property.get$ref() != null) {
            Schema<Object> reference = new Schema<>();
            reference.set$ref(property.get$ref());
            ComposedSchema union = new ComposedSchema();
            union.setDescription(property.getDescription()); // 원 속성 설명 유지
            union.addOneOfItem(reference);
            union.addOneOfItem(nullSchema());
            return union;
        }
        Schema copy = AnnotationsUtils.clone(property, property.getSpecVersion() == SpecVersion.V31);
        if (copy.getOneOf() != null) {
            copy.addOneOfItem(nullSchema()); // 기존 oneOf → null 분기 추가
        } else {
            copy.addType("null");
            if (copy.getEnum() != null && !copy.getEnum().contains(null)) {
                copy.addEnumItemObject(null); // 인라인 enum은 enum 목록에도 null이 있어야 null 값이 검증을 통과한다
            }
        }
        return copy;
    }

    private static Schema<Object> nullSchema() {
        Schema<Object> nullSchema = new Schema<>();
        nullSchema.addType("null");
        return nullSchema;
    }

    // types에 null(enum이 있으면 enum에도 null) 또는 oneOf에 null 분기
    private static boolean allowsNull(Schema<?> property) {
        if (property.getTypes() != null && property.getTypes().contains("null")) {
            return property.getEnum() == null || property.getEnum().contains(null);
        }
        return property.getOneOf() != null && property.getOneOf().stream().anyMatch(NullMarkedRecordModelConverter::allowsNull);
    }
}
