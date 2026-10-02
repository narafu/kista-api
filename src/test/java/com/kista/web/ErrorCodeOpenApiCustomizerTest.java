package com.kista.web;

import com.kista.platform.web.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeOpenApiCustomizerTest {

    @Test
    void registersErrorCodeEnumSchema() {
        OpenAPI openApi = new OpenAPI();
        new ErrorCodeOpenApiCustomizer().customise(openApi);

        var schema = openApi.getComponents().getSchemas().get("ErrorCode");
        assertThat(schema.getType()).isEqualTo("string");
        assertThat(schema.getEnum()).containsExactlyElementsOf(
                Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }
}
