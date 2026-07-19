package com.kista.adapter.in.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HousingBenchmarkOpenApiCustomizer implements OpenApiCustomizer {
    private static final String RESPONSE_SCHEMA = "HousingBenchmarkComparisonResponse";
    private static final List<String> NULLABLE_REFERENCE_PROPERTIES =
            List.of("strategy", "summary", "currentExchangeRate");

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }

        Schema<?> responseSchema = openApi.getComponents().getSchemas().get(RESPONSE_SCHEMA);
        if (responseSchema == null || responseSchema.getProperties() == null) {
            return;
        }

        NULLABLE_REFERENCE_PROPERTIES.forEach(propertyName ->
                wrapNullableReference(responseSchema, propertyName));
    }

    private static void wrapNullableReference(Schema<?> responseSchema, String propertyName) {
        Schema<?> property = (Schema<?>) responseSchema.getProperties().get(propertyName);
        if (property == null || property.get$ref() == null) {
            return;
        }

        Schema<Object> reference = new Schema<>();
        reference.set$ref(property.get$ref());
        Schema<Object> nullSchema = new Schema<>();
        nullSchema.addType("null");
        ComposedSchema nullableReference = new ComposedSchema();
        nullableReference.addOneOfItem(reference);
        nullableReference.addOneOfItem(nullSchema);
        responseSchema.getProperties().put(
                propertyName,
                nullableReference);
    }
}
