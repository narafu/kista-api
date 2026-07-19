package com.kista.adapter.in.web.dto;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HousingBenchmarkComparisonResponseSchemaTest {

    @Test
    void openApi31_스키마는_실제_null_응답_필드를_nullable_union으로_생성한다() {
        Map<String, Schema> schemas = ModelConverters.getInstance(true)
                .readAll(HousingBenchmarkComparisonResponse.class);
        Schema<?> response = schemas.get("HousingBenchmarkComparisonResponse");

        assertNullable(property(response, "currentExchangeRate"), "currentExchangeRate");
        assertNullable(property(response, "strategy"), "strategy");
        assertNullable(property(response, "summary"), "summary");
        assertNullable(property(response, "emptyReason"), "emptyReason");

        Schema<?> benchmark = dereference(schemas, property(response, "benchmark"));
        assertNullable(property(benchmark, "sourceUpdatedDate"), "benchmark.sourceUpdatedDate");

        Schema<?> period = dereference(schemas, property(response, "period"));
        assertNullable(property(period, "fromMonth"), "period.fromMonth");
        assertNullable(property(period, "toMonth"), "period.toMonth");

        Schema<?> point = dereference(schemas, property(response, "points").getItems());
        assertNullable(property(point, "investmentMonthlyReturn"),
                "points[].investmentMonthlyReturn");
        assertNullable(property(point, "benchmarkMonthlyReturn"),
                "points[].benchmarkMonthlyReturn");
    }

    private static Schema<?> property(Schema<?> schema, String name) {
        assertThat(schema).as("owner schema for %s", name).isNotNull();
        assertThat(schema.getProperties()).as("properties for %s", name).containsKey(name);
        return (Schema<?>) schema.getProperties().get(name);
    }

    private static Schema<?> dereference(Map<String, Schema> schemas, Schema<?> schema) {
        if (schema.get$ref() == null) {
            return schema;
        }
        String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        return schemas.get(name);
    }

    private static void assertNullable(Schema<?> schema, String path) {
        assertThat(schema.getTypes()).as(path).contains("null");
    }
}
