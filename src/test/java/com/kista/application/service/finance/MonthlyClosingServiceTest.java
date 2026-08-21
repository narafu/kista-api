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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("list는 findCurrentGroupId로 얻은 currentGroupId로 조회")
    void list_queriesWithCurrentGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        MonthlyClosing closing = new MonthlyClosing(UUID.randomUUID(), groupId, userId, "2026-01", false, null, null);
        when(monthlyClosingPort.findMyScope(userId, groupId)).thenReturn(List.of(closing));

        List<MonthlyClosing> result = monthlyClosingService.list(userId, null);

        assertThat(result).hasSize(1);
        verify(monthlyClosingPort).findMyScope(userId, groupId);
    }

    @Test
    @DisplayName("올바른 'YYYY-MM' 형식 + 무그룹 유저면 upsert가 groupId=null로 호출됨(개인 마감)")
    void setCompleted_validMonth_noCurrentGroup_upsertsWithNullGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        MonthlyClosing closed = new MonthlyClosing(UUID.randomUUID(), null, userId, "2026-08", true, null, null);
        when(monthlyClosingPort.upsert(null, userId, "2026-08", true)).thenReturn(closed);

        MonthlyClosing result = monthlyClosingService.setCompleted(userId, null, "2026-08", true);

        assertThat(result).isEqualTo(closed);
        verify(monthlyClosingPort).upsert(null, userId, "2026-08", true);
    }

    @Test
    @DisplayName("현재 그룹이 있으면 upsert가 groupId로 호출됨(그룹 마감)")
    void setCompleted_validMonth_hasCurrentGroup_upsertsWithGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        MonthlyClosing closed = new MonthlyClosing(UUID.randomUUID(), groupId, userId, "2026-08", true, null, null);
        when(monthlyClosingPort.upsert(groupId, userId, "2026-08", true)).thenReturn(closed);

        monthlyClosingService.setCompleted(userId, null, "2026-08", true);

        verify(monthlyClosingPort).upsert(groupId, userId, "2026-08", true);
    }

    @Test
    @DisplayName("잘못된 형식('2026-13')은 DateTimeParseException을 그대로 전파 (400 매핑은 GlobalExceptionHandler 책임)")
    void setCompleted_malformedMonth_invalidRange_propagatesParseException() {
        assertThatThrownBy(() -> monthlyClosingService.setCompleted(userId, null, "2026-13", true))
                .isInstanceOf(DateTimeParseException.class);

        verifyNoInteractions(monthlyClosingPort);
    }

    @Test
    @DisplayName("잘못된 형식('August')은 DateTimeParseException을 그대로 전파")
    void setCompleted_malformedMonth_notNumeric_propagatesParseException() {
        assertThatThrownBy(() -> monthlyClosingService.setCompleted(userId, null, "August", true))
                .isInstanceOf(DateTimeParseException.class);

        verifyNoInteractions(monthlyClosingPort);
    }

    // V17 이후 userId는 영구 소유자 축 — completed=false(마감 해제)로 전환해도 upsert에 null이 아닌
    // 실제 userId가 그대로 넘어가야 한다(옛 closedBy처럼 null로 되돌리지 않음).
    @Test
    @DisplayName("completed=false(마감 해제)여도 upsert는 userId를 null로 되돌리지 않는다")
    void setCompleted_false_stillPassesRealUserId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(monthlyClosingPort.upsert(groupId, userId, "2026-08", false))
                .thenReturn(new MonthlyClosing(UUID.randomUUID(), groupId, userId, "2026-08", false, null, null));

        monthlyClosingService.setCompleted(userId, null, "2026-08", false);

        verify(monthlyClosingPort).upsert(groupId, userId, "2026-08", false);
    }
}
