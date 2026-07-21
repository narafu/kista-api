package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NextOrdersResponseTest {

    @Test
    void from_mapsCompetitionAsNull_whenPreviewCompetitionIsNull() {
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition()).isNull();
    }

    @Test
    void from_mapsCompetitionFields_whenPreviewCompetitionExists() {
        UUID competitorId = UUID.randomUUID();
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                false, new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("900.00"),
                List.of(new BuyCompetitionPreview.CompetingStrategy(
                        competitorId, Strategy.Type.VR, Ticker.TQQQ, new BigDecimal("900.00"), 0)),
                List.of(), false);
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, competition);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition()).isNotNull();
        assertThat(response.competition().sufficientBudget()).isFalse();
        assertThat(response.competition().availableDeposit()).isEqualByComparingTo("1000.00");
        assertThat(response.competition().blockedByHigherPriority()).hasSize(1);
        assertThat(response.competition().blockedByHigherPriority().get(0).strategyId()).isEqualTo(competitorId);
        assertThat(response.competition().blockedByHigherPriority().get(0).type()).isEqualTo(Strategy.Type.VR);
    }
}
