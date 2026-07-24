package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.SellSufficiencyPreview;
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
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, null);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition()).isNull();
        assertThat(response.sellSufficiency()).isNull();
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
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, competition, null);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition()).isNotNull();
        assertThat(response.competition().sufficientBudget()).isFalse();
        assertThat(response.competition().availableDeposit()).isEqualByComparingTo("1000.00");
        assertThat(response.competition().blockedByHigherPriority()).hasSize(1);
        assertThat(response.competition().blockedByHigherPriority().get(0).strategyId()).isEqualTo(competitorId);
        assertThat(response.competition().blockedByHigherPriority().get(0).type()).isEqualTo(Strategy.Type.VR);
        assertThat(response.competition().liveBalanceUnavailable()).isFalse();
    }

    @Test
    void from_mapsLiveBalanceUnavailable_whenCompetitionUnavailable() {
        BuyCompetitionPreview competition = BuyCompetitionPreview.unavailable(new BigDecimal("200.00"));
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, competition, null);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition().liveBalanceUnavailable()).isTrue();
        assertThat(response.competition().availableDeposit()).isNull();
    }

    @Test
    void from_mapsSellSufficiencyAsNull_whenPreviewSellSufficiencyIsNull() {
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, null);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.sellSufficiency()).isNull();
    }

    @Test
    void from_mapsSellSufficiencyFields_whenPreviewSellSufficiencyExists() {
        SellSufficiencyPreview sellSufficiency = new SellSufficiencyPreview(false, 5, 3, 3, false);
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, sellSufficiency);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.sellSufficiency()).isNotNull();
        assertThat(response.sellSufficiency().sufficientQuantity()).isFalse();
        assertThat(response.sellSufficiency().sellableQuantity()).isEqualTo(5);
        assertThat(response.sellSufficiency().reservedQuantity()).isEqualTo(3);
        assertThat(response.sellSufficiency().requiredQuantity()).isEqualTo(3);
        assertThat(response.sellSufficiency().liveQuantityUnavailable()).isFalse();
    }

    @Test
    void from_mapsLiveQuantityUnavailable_whenSellSufficiencyUnavailable() {
        SellSufficiencyPreview sellSufficiency = SellSufficiencyPreview.unavailable(3);
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, sellSufficiency);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.sellSufficiency().liveQuantityUnavailable()).isTrue();
        assertThat(response.sellSufficiency().sellableQuantity()).isEqualTo(0);
    }
}
