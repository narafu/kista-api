package com.kista.web;

import com.kista.platform.web.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;

// ProblemDetail "code" 값 집합을 openapi.json components.schemas.ErrorCode로 노출 — kista-ui gen:types가 타입 안전 매핑에 사용.
// 엔드포인트가 참조하지 않는 독립 스키마라 springdoc이 자동 생성하지 않으므로 직접 등록한다
@Component
public class ErrorCodeOpenApiCustomizer implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) openApi.setComponents(new Components());
        StringSchema schema = new StringSchema();
        Arrays.stream(ErrorCode.values()).map(Enum::name).forEach(schema::addEnumItem);
        openApi.getComponents().addSchemas("ErrorCode", schema);
    }
}
