package com.kista.tradingcore.stats.adapter.in.web.openapi;

import com.kista.tradingcore.stats.adapter.in.web.dto.CryptoMarketIndicatorsResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 코인 시장 지표 응답 스키마 — kista-ui 생성 타입 계약(null 섹션은 `X | null`, 모든 키 required) 잠금
class CryptoMarketIndicatorsOpenApiCustomizerTest {

    @SuppressWarnings("rawtypes")
    private final Map<String, Schema> schemas = ModelConverters.getInstance(true).readAll(CryptoMarketIndicatorsResponse.class);

    {
        new CryptoMarketIndicatorsOpenApiCustomizer().customise(new OpenAPI().components(new Components().schemas(schemas)));
    }

    @Test
    void 실패시_null_섹션은_ref와_null_oneOf로_생성한다() {
        assertNullableReference(property("CryptoMarketIndicatorsResponse", "usdtPremium"), "CryptoMarketIndicatorsPremium");
        assertNullableReference(property("CryptoMarketIndicatorsTicker", "kimchiPremium"), "CryptoMarketIndicatorsKimchiPremium");
        assertNullableReference(property("CryptoMarketIndicatorsTicker", "fundingRate"), "CryptoMarketIndicatorsFundingRate");
        assertNullableReference(property("CryptoMarketIndicatorsTicker", "openInterest"), "CryptoMarketIndicatorsOpenInterest");
        assertNullableReference(property("CryptoMarketIndicatorsTicker", "valuation"), "CryptoMarketIndicatorsValuation");
        assertNullableReference(property("CryptoMarketIndicatorsTicker", "longShortRatio"), "CryptoMarketIndicatorsLongShortRatio");
    }

    @Test
    void null_가능_스칼라는_null_타입을_포함한다() {
        assertThat(property("CryptoMarketIndicatorsPremiumPair", "official").getTypes()).contains("null");
        assertThat(property("CryptoMarketIndicatorsDatedPremiumPair", "official").getTypes()).contains("null");
        assertThat(property("CryptoMarketIndicatorsDatedValuation", "wma200").getTypes()).contains("null");
        assertThat(property("CryptoMarketIndicatorsDatedValuation", "wma200Multiple").getTypes()).contains("null");
        assertThat(property("CryptoMarketIndicatorsDatedValuation", "mayerMultiple").getTypes()).contains("null");
    }

    @Test
    void 스키마명은_고유_접두사를_가진다() {
        assertThat(schemas.keySet()).allMatch(name -> name.startsWith("CryptoMarketIndicators"));
    }

    @Test
    void 모든_속성은_required다() {
        schemas.forEach((name, schema) ->
                assertThat(schema.getRequired()).as(name).containsExactlyInAnyOrderElementsOf(schema.getProperties().keySet()));
    }

    private Schema<?> property(String schemaName, String propertyName) {
        return (Schema<?>) schemas.get(schemaName).getProperties().get(propertyName);
    }

    private static void assertNullableReference(Schema<?> property, String refName) {
        assertThat(property.get$ref()).isNull();
        assertThat(property.getOneOf()).hasSize(2);
        assertThat(property.getOneOf()).anySatisfy(branch -> assertThat(branch.get$ref()).endsWith("/" + refName));
        assertThat(property.getOneOf()).anySatisfy(branch -> assertThat(branch.getTypes()).containsExactly("null"));
    }
}
