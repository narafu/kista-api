package com.kista.tradingcore.stats.adapter.in.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 코인 시장 지표 응답의 실패 시 null 섹션을 `oneOf [$ref, null]`로 바꾼다 — OAS 3.1에서 springdoc은 $ref 속성의
// nullable=true를 버려 kista-ui 생성 타입이 null을 모른다(root HousingBenchmarkOpenApiCustomizer와 같은 처리)
@Slf4j
@Component
public class CryptoMarketIndicatorsOpenApiCustomizer implements OpenApiCustomizer {
    // 스키마명 → null 가능 객체 속성
    private static final Map<String, List<String>> NULLABLE_REFERENCE_PROPERTIES = Map.of(
            "CryptoMarketIndicatorsResponse", List.of("usdtPremium", "halving"),
            "CryptoMarketIndicatorsTicker", List.of("kimchiPremium", "fundingRate", "openInterest", "valuation", "longShortRatio"));

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        NULLABLE_REFERENCE_PROPERTIES.forEach((schemaName, propertyNames) -> {
            Schema<?> schema = openApi.getComponents().getSchemas().get(schemaName);
            if (schema == null || schema.getProperties() == null) {
                log.warn("OpenAPI 스키마 {} 없음 — null 섹션 oneOf 변환 건너뜀(kista-ui 생성 타입에서 null 누락)", schemaName);
                return;
            }
            propertyNames.forEach(propertyName -> wrapNullableReference(schema, propertyName));
        });
    }

    private static void wrapNullableReference(Schema<?> schema, String propertyName) {
        Schema<?> property = schema.getProperties().get(propertyName);
        if (property == null || property.get$ref() == null) {
            return;
        }
        Schema<Object> reference = new Schema<>();
        reference.set$ref(property.get$ref());
        Schema<Object> nullSchema = new Schema<>();
        nullSchema.addType("null");
        ComposedSchema nullableReference = new ComposedSchema();
        nullableReference.setDescription(property.getDescription()); // 원 속성 설명 유지
        nullableReference.addOneOfItem(reference);
        nullableReference.addOneOfItem(nullSchema);
        schema.getProperties().put(propertyName, nullableReference);
    }
}
