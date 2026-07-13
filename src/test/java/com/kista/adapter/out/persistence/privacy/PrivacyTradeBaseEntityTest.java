package com.kista.adapter.out.persistence.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyTradeBaseEntityTest {

    @Test
    void maps_release_date_column_to_distinguish_privacy_base_date_from_order_trade_date() throws NoSuchFieldException {
        Column tradeDateColumn = PrivacyTradeBaseEntity.class
                .getDeclaredField("tradeDate")
                .getAnnotation(Column.class);
        Table table = PrivacyTradeBaseEntity.class.getAnnotation(Table.class);
        List<String> uniqueColumnNames = Arrays.stream(table.uniqueConstraints())
                .flatMap(constraint -> Arrays.stream(constraint.columnNames()))
                .toList();

        assertThat(tradeDateColumn.name()).isEqualTo("release_date");
        assertThat(uniqueColumnNames).containsExactly("release_date", "ticker");
        assertThat(Arrays.stream(table.uniqueConstraints()).map(UniqueConstraint::name))
                .contains("uq_privacy_trade_bases_release_date_ticker");
    }
}
