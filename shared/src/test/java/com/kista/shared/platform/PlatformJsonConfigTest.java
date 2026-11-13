package com.kista.shared.platform;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformJsonConfigTest {

    // PlatformJsonConfig의 customizer를 매퍼 빌더에 적용해 BigDecimal이 과학 표기 없이 number 토큰으로 나가는지 확인
    @Test
    void BigDecimal은_과학표기_없이_plain_number로_직렬화된다() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new PlatformJsonConfig().plainBigDecimalCustomizer().customize(builder);
        JsonMapper mapper = builder.build();

        assertThat(mapper.writeValueAsString(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(mapper.writeValueAsString(new BigDecimal("5E-7"))).isEqualTo("0.0000005");
    }
}
