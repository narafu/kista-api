package com.kista.api.finance.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.api.finance.domain.model.FinanceAccount;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record FinanceAccountResponse(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "그룹 ID (개인 소유면 null)")
        @Nullable UUID groupId,
        @Schema(description = "계좌 타입", example = "SECURITIES")
        FinanceAccount.Type accountType,
        @Schema(description = "계좌명", example = "토스증권 일반계좌")
        String name,
        @Schema(description = "계좌번호")
        @Nullable String accountNo,
        @Schema(description = "메모")
        @Nullable String memo,
        @Schema(description = "기관")
        @Nullable String institution,
        @Schema(description = "소유자")
        @Nullable String owner
) {
    public static FinanceAccountResponse from(FinanceAccount a) {
        return new FinanceAccountResponse(a.id(), a.groupId(), a.accountType(), a.name(), a.accountNo(), a.memo(),
                a.institution(), a.owner());
    }
}
