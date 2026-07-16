package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TossResponseParser — Toss API 응답 파싱 헬퍼 테스트")
class TossResponseParserTest {

    @Test
    @DisplayName("parseBdOrZero — null이면 ZERO 반환")
    void parseBdOrZero_null_returns_zero() {
        assertThat(TossResponseParser.parseBdOrZero(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("parseBdOrZero — 공백 문자열이면 ZERO 반환")
    void parseBdOrZero_blank_returns_zero() {
        assertThat(TossResponseParser.parseBdOrZero("  ")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("parseBdOrZero — 정상 숫자 문자열은 BigDecimal로 변환")
    void parseBdOrZero_valid_number_parses() {
        assertThat(TossResponseParser.parseBdOrZero("123.45")).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    @DisplayName("parseIntOrZero — null이면 0 반환")
    void parseIntOrZero_null_returns_zero() {
        assertThat(TossResponseParser.parseIntOrZero(null)).isZero();
    }

    @Test
    @DisplayName("parseIntOrZero — 공백 문자열이면 0 반환")
    void parseIntOrZero_blank_returns_zero() {
        assertThat(TossResponseParser.parseIntOrZero("  ")).isZero();
    }

    @Test
    @DisplayName("parseIntOrZero — 정상 정수 문자열은 int로 변환")
    void parseIntOrZero_valid_number_parses() {
        assertThat(TossResponseParser.parseIntOrZero("42")).isEqualTo(42);
    }

    @Test
    @DisplayName("unwrap — wrapper가 null이면 TossApiException 발생")
    void unwrap_null_wrapper_throws() {
        assertThatThrownBy(() -> TossResponseParser.unwrap(null))
                .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("unwrap — wrapper.result()가 null이면 TossApiException 발생")
    void unwrap_null_result_throws() {
        TossResult<String> wrapper = new TossResult<>(null);

        assertThatThrownBy(() -> TossResponseParser.unwrap(wrapper))
                .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("unwrap — 정상 wrapper는 result를 그대로 반환")
    void unwrap_valid_wrapper_returns_result() {
        TossResult<String> wrapper = new TossResult<>("ok");

        assertThat(TossResponseParser.unwrap(wrapper)).isEqualTo("ok");
    }
}
