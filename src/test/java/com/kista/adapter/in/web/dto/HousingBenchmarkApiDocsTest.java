package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.openapi.HousingBenchmarkOpenApiCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = HousingBenchmarkApiDocsTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.path=/api-docs")
class HousingBenchmarkApiDocsTest {
    @LocalServerPort
    int port;

    @Test
    void apiDocs는_nullable_객체_ref와_null을_별도_oneOf_분기로_생성한다() throws Exception {
        TestRestTemplate restTemplate = new TestRestTemplate();

        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode schema = new ObjectMapper().readTree(response.getBody())
                .path("components").path("schemas")
                .path("HousingBenchmarkComparisonResponse")
                .path("properties");
        assertNullableReferenceUnion(schema.path("strategy"), "StrategyInfo");
        assertNullableReferenceUnion(schema.path("summary"), "Summary");
        assertNullableReferenceUnion(schema.path("currentExchangeRate"), "CurrentExchangeRate");
    }

    private static void assertNullableReferenceUnion(JsonNode property, String refName) {
        JsonNode oneOf = property.path("oneOf");
        assertThat(oneOf.isArray()).as(refName + " oneOf").isTrue();
        List<JsonNode> branches = new ArrayList<>();
        oneOf.forEach(branches::add);
        assertThat(branches).as(refName + " union branches").hasSize(2);
        JsonNode referenceBranch = branches.stream()
                .filter(branch -> branch.has("$ref"))
                .findFirst()
                .orElseThrow();
        JsonNode nullBranch = branches.stream()
                .filter(branch -> "null".equals(branch.path("type").asText()))
                .findFirst()
                .orElseThrow();

        assertThat(referenceBranch).isNotSameAs(nullBranch);
        assertThat(referenceBranch.path("$ref").asText()).endsWith("/" + refName);
        assertThat(referenceBranch.has("type")).isFalse();
        assertThat(nullBranch.has("$ref")).isFalse();
        assertThat(property.has("$ref")).isFalse();
        assertThat(property.has("type")).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class
    })
    @Import({SchemaController.class, HousingBenchmarkOpenApiCustomizer.class})
    static class TestApplication {}

    @RestController
    static class SchemaController {
        @GetMapping("/housing-benchmark-schema")
        HousingBenchmarkComparisonResponse schema() {
            return null;
        }
    }
}
