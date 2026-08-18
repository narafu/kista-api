package com.kista.application.service.finance;

import com.kista.domain.model.finance.MonthlyClosing;
import com.kista.domain.port.out.FinanceGroupPort;
import com.kista.domain.port.out.MonthlyClosingPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyClosingService 단위 테스트")
class MonthlyClosingServiceTest {

    @Mock MonthlyClosingPort monthlyClosingPort;
    @Mock FinanceGroupPort financeGroupPort;
    @InjectMocks MonthlyClosingService monthlyClosingService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    @DisplayName("list는 resolveGroupId로 얻은 groupId로 조회")
    void list_resolvesGroupId() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        MonthlyClosing closing = new MonthlyClosing(UUID.randomUUID(), groupId, null, "2026-01", false, null, null);
        when(monthlyClosingPort.findByGroupId(groupId)).thenReturn(List.of(closing));

        List<MonthlyClosing> result = monthlyClosingService.list(userId, null);

        assertThat(result).hasSize(1);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("올바른 'YYYY-MM' 형식이면 upsert가 올바른 인자로 호출됨")
    void setCompleted_validMonth_callsUpsertWithCorrectArgs() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        MonthlyClosing closed = new MonthlyClosing(UUID.randomUUID(), groupId, userId, "2026-08", true, null, null);
        when(monthlyClosingPort.upsert(groupId, userId, "2026-08", true)).thenReturn(closed);

        MonthlyClosing result = monthlyClosingService.setCompleted(userId, null, "2026-08", true);

        assertThat(result).isEqualTo(closed);
        verify(monthlyClosingPort).upsert(groupId, userId, "2026-08", true);
    }

    @Test
    @DisplayName("잘못된 형식('2026-13')은 DateTimeParseException을 그대로 전파 (400 매핑은 GlobalExceptionHandler 책임)")
    void setCompleted_malformedMonth_invalidRange_propagatesParseException() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);

        assertThatThrownBy(() -> monthlyClosingService.setCompleted(userId, null, "2026-13", true))
                .isInstanceOf(DateTimeParseException.class);

        verifyNoInteractions(monthlyClosingPort);
    }

    @Test
    @DisplayName("잘못된 형식('August')은 DateTimeParseException을 그대로 전파")
    void setCompleted_malformedMonth_notNumeric_propagatesParseException() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);

        assertThatThrownBy(() -> monthlyClosingService.setCompleted(userId, null, "August", true))
                .isInstanceOf(DateTimeParseException.class);

        verifyNoInteractions(monthlyClosingPort);
    }

    @Test
    @DisplayName("completed=true면 upsert가 closedBy=userId(non-null)로 호출됨")
    void setCompleted_true_upsertsWithNonNullClosedBy() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(monthlyClosingPort.upsert(eq(groupId), eq(userId), eq("2026-08"), eq(true)))
                .thenReturn(new MonthlyClosing(UUID.randomUUID(), groupId, userId, "2026-08", true, null, null));

        monthlyClosingService.setCompleted(userId, null, "2026-08", true);

        verify(monthlyClosingPort).upsert(groupId, userId, "2026-08", true);
    }

    @Test
    @DisplayName("completed=false면 upsert가 closedBy=null로 호출됨 (마감 해제)")
    void setCompleted_false_upsertsWithNullClosedBy() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(monthlyClosingPort.upsert(eq(groupId), isNull(), eq("2026-08"), eq(false)))
                .thenReturn(new MonthlyClosing(UUID.randomUUID(), groupId, null, "2026-08", false, null, null));

        monthlyClosingService.setCompleted(userId, null, "2026-08", false);

        verify(monthlyClosingPort).upsert(groupId, null, "2026-08", false);
    }
}
