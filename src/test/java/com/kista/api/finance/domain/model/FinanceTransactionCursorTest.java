package com.kista.api.finance.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinanceTransactionCursorTest {

    private static final FinanceTransactionSort MEMO_ASC = new FinanceTransactionSort(FinanceTransactionSort.Key.MEMO, true);
    private static final FinanceTransactionSort DATE_DESC = FinanceTransactionSort.DEFAULT;

    @Test
    void roundTrip_value() {
        UUID id = UUID.randomUUID();
        var cursor = new FinanceTransactionCursor(DATE_DESC, "2026-08-01", id);
        assertThat(FinanceTransactionCursor.decode(cursor.encode(), DATE_DESC)).isEqualTo(cursor);
    }

    @Test
    void roundTrip_valueWithPipe() {
        var cursor = new FinanceTransactionCursor(MEMO_ASC, "a|b|c", UUID.randomUUID());
        assertThat(FinanceTransactionCursor.decode(cursor.encode(), MEMO_ASC)).isEqualTo(cursor);
    }

    @Test
    void roundTrip_nullValue() {
        var cursor = new FinanceTransactionCursor(MEMO_ASC, null, UUID.randomUUID());
        assertThat(FinanceTransactionCursor.decode(cursor.encode(), MEMO_ASC)).isEqualTo(cursor);
    }

    @Test
    void decode_invalid_throws() {
        String other = new FinanceTransactionCursor(DATE_DESC, "2026-08-01", UUID.randomUUID()).encode();
        String badDate = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("transactionDate,desc|" + UUID.randomUUID() + "|Vnot-a-date").getBytes());
        for (String raw : new String[]{"!!!", other /* sort 불일치 */, badDate, ""}) {
            assertThatThrownBy(() -> FinanceTransactionCursor.decode(raw, MEMO_ASC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("커서가 올바르지 않습니다.");
        }
        assertThatThrownBy(() -> FinanceTransactionCursor.decode(badDate, DATE_DESC))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
