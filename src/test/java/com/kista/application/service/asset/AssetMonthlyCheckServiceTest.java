package com.kista.application.service.asset;

import com.kista.domain.model.asset.AssetMonthlyCheck;
import com.kista.domain.port.out.AssetMonthlyCheckPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetMonthlyCheckService 단위 테스트")
class AssetMonthlyCheckServiceTest {

    @Mock AssetMonthlyCheckPort assetMonthlyCheckPort;
    @InjectMocks AssetMonthlyCheckService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 연월이면 upsert 위임")
    void setCompleted_valid_month_delegates() {
        when(assetMonthlyCheckPort.upsert(userId, "2026-08", true))
                .thenReturn(new AssetMonthlyCheck("2026-08", true));

        AssetMonthlyCheck result = service.setCompleted(userId, "2026-08", true);

        assertThat(result.completed()).isTrue();
        verify(assetMonthlyCheckPort).upsert(userId, "2026-08", true);
    }

    @Test
    @DisplayName("잘못된 연월 형식이면 DateTimeParseException 발생 (→ GlobalExceptionHandler가 400 매핑)")
    void setCompleted_invalid_month_format_throws() {
        assertThatThrownBy(() -> service.setCompleted(userId, "2026/08", true))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    @DisplayName("존재하지 않는 월(13)이면 DateTimeParseException 발생 (→ 400)")
    void setCompleted_outOfRange_month_throws() {
        assertThatThrownBy(() -> service.setCompleted(userId, "2026-13", true))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    @DisplayName("listByUser: userId로 완료 상태 목록 위임")
    void listByUser_delegates() {
        when(assetMonthlyCheckPort.findByUserId(userId))
                .thenReturn(List.of(new AssetMonthlyCheck("2026-08", true)));

        assertThat(service.listByUser(userId)).hasSize(1);
    }
}
