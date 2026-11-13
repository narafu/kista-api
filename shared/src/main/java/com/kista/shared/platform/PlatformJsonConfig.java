package com.kista.shared.platform;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

// 전역 JSON 직렬화 설정 — PlatformAutoConfiguration이 @Import하고, 자동구성을 로드하지 않는 @WebMvcTest 슬라이스도 직접 @Import한다
@Configuration(proxyBeanMethods = false)
public class PlatformJsonConfig {

    // 전역 BigDecimal 직렬화를 plain number로 — 1E+2 → 100, 5E-7 → 0.0000005 (과학 표기 방지, JSON은 number 토큰 유지)
    @Bean
    public JsonMapperBuilderCustomizer plainBigDecimalCustomizer() {
        return builder -> builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
