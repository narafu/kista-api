package com.kista.api.finance.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinanceTransactionSortTest {

    @Test
    void parse_nullOrBlank_returnsDefault() {
        assertThat(FinanceTransactionSort.parse(null)).isEqualTo(FinanceTransactionSort.DEFAULT);
        assertThat(FinanceTransactionSort.parse(" ")).isEqualTo(FinanceTransactionSort.DEFAULT);
        assertThat(FinanceTransactionSort.DEFAULT.wire()).isEqualTo("transactionDate,desc");
    }

    @Test
    void parse_allFieldsAndDirections() {
        assertThat(FinanceTransactionSort.parse("category,desc")).isEqualTo(new FinanceTransactionSort(FinanceTransactionSort.Key.CATEGORY, false));
        assertThat(FinanceTransactionSort.parse("memo,asc")).isEqualTo(new FinanceTransactionSort(FinanceTransactionSort.Key.MEMO, true));
    }

    @Test
    void parse_invalid_throws() {
        for (String raw : new String[]{"Amount,asc", "amount,ASC", "amount,up", "price,asc", "amount,asc,x", ",asc", "amount"}) {
            assertThatThrownBy(() -> FinanceTransactionSort.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("정렬 기준이 올바르지 않습니다.");
        }
    }
}
