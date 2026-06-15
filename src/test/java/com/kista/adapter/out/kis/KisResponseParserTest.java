package com.kista.adapter.out.kis;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class KisResponseParserTest {

    // parseBd: null/blank→ZERO, 유효값→값, 오류→ZERO
    @Test
    void parseBd_null_returnsZero() {
        assertThat(KisResponseParser.parseBd(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void parseBd_blank_returnsZero() {
        assertThat(KisResponseParser.parseBd("   ")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void parseBd_validNumber_returnsValue() {
        assertThat(KisResponseParser.parseBd("123.45")).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    void parseBd_invalidString_returnsZero() {
        assertThat(KisResponseParser.parseBd("abc")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // parseIntSafe: null/blank→0, 정수→값, 소수표현→값, 오류→0
    @Test
    void parseIntSafe_null_returnsZero() {
        assertThat(KisResponseParser.parseIntSafe(null)).isEqualTo(0);
    }

    @Test
    void parseIntSafe_integerString_returnsValue() {
        assertThat(KisResponseParser.parseIntSafe("5")).isEqualTo(5);
    }

    @Test
    void parseIntSafe_decimalString_returnsIntPart() {
        assertThat(KisResponseParser.parseIntSafe("5.0")).isEqualTo(5);
    }

    @Test
    void parseIntSafe_invalidString_returnsZero() {
        assertThat(KisResponseParser.parseIntSafe("abc")).isEqualTo(0);
    }

    // parseDirection: "01"→SELL, 나머지→BUY
    @Test
    void parseDirection_01_returnsSell() {
        assertThat(KisResponseParser.parseDirection("01")).isEqualTo(Order.OrderDirection.SELL);
    }

    @Test
    void parseDirection_02_returnsBuy() {
        assertThat(KisResponseParser.parseDirection("02")).isEqualTo(Order.OrderDirection.BUY);
    }

    @Test
    void parseDirection_unknown_returnsBuy() {
        assertThat(KisResponseParser.parseDirection("99")).isEqualTo(Order.OrderDirection.BUY);
    }

    // formatPrice: MOC→"0", LOC/LIMIT→소수 2자리
    @Test
    void formatPrice_moc_returnsZeroString() {
        assertThat(KisResponseParser.formatPrice(Order.OrderType.MOC, new BigDecimal("22.5"))).isEqualTo("0");
    }

    @Test
    void formatPrice_loc_returnsTwoDecimalPlaces() {
        assertThat(KisResponseParser.formatPrice(Order.OrderType.LOC, new BigDecimal("22.5"))).isEqualTo("22.50");
    }

    @Test
    void formatPrice_limit_returnsTwoDecimalPlaces() {
        assertThat(KisResponseParser.formatPrice(Order.OrderType.LIMIT, new BigDecimal("22"))).isEqualTo("22.00");
    }

    // resolvePrice: last 유효→last, last 빈+base 유효→base, 둘 다 빈→empty
    @Test
    void resolvePrice_validLast_returnsLast() {
        Optional<BigDecimal> result = KisResponseParser.resolvePrice("10.50", "9.00");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo(new BigDecimal("10.50"));
    }

    @Test
    void resolvePrice_emptyLastValidBase_returnsBase() {
        Optional<BigDecimal> result = KisResponseParser.resolvePrice("", "9.00");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo(new BigDecimal("9.00"));
    }

    @Test
    void resolvePrice_bothEmpty_returnsEmpty() {
        assertThat(KisResponseParser.resolvePrice("", "")).isEmpty();
    }

    // parseDate: 유효 YYYYMMDD→LocalDate, null→fallback, 오류→fallback
    @Test
    void parseDate_validString_returnsLocalDate() {
        LocalDate result = KisResponseParser.parseDate("20240615", LocalDate.of(2000, 1, 1));
        assertThat(result).isEqualTo(LocalDate.of(2024, 6, 15));
    }

    @Test
    void parseDate_null_returnsFallback() {
        LocalDate fallback = LocalDate.of(2000, 1, 1);
        assertThat(KisResponseParser.parseDate(null, fallback)).isEqualTo(fallback);
    }

    @Test
    void parseDate_invalidString_returnsFallback() {
        LocalDate fallback = LocalDate.of(2000, 1, 1);
        assertThat(KisResponseParser.parseDate("invalid", fallback)).isEqualTo(fallback);
    }

    // streamTickered: null→empty, 알 수 없는 종목 silent drop, 알려진 종목 매핑
    @Test
    void streamTickered_nullRows_returnsEmpty() {
        List<String> result = KisResponseParser.streamTickered(null, Function.identity(), (ticker, row) -> row);
        assertThat(result).isEmpty();
    }

    @Test
    void streamTickered_unknownTickerDropped() {
        List<String> rows = List.of("SOXL", "UNKNOWN", "TQQQ");
        List<String> result = KisResponseParser.streamTickered(rows, Function.identity(), (ticker, row) -> row);
        assertThat(result).containsExactly("SOXL", "TQQQ");
    }

    @Test
    void streamTickered_allUnknown_returnsEmpty() {
        List<String> rows = List.of("UNKNOWN1", "UNKNOWN2");
        List<String> result = KisResponseParser.streamTickered(rows, Function.identity(), (ticker, row) -> row);
        assertThat(result).isEmpty();
    }

    @Test
    void streamTickered_tickerPassedToMapper() {
        List<String> rows = List.of("SOXL");
        List<Ticker> result = KisResponseParser.streamTickered(rows, Function.identity(), (ticker, row) -> ticker);
        assertThat(result).containsExactly(Ticker.SOXL);
    }
}
