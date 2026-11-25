package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.FinanceGroupInvitation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record FinanceGroupInvitationStatusRequest(
        @Schema(description = "ACCEPTED 또는 DECLINED", example = "ACCEPTED")
        @NotNull(message = "초대 응답 상태는 필수입니다.") FinanceGroupInvitation.Status status
) {}
