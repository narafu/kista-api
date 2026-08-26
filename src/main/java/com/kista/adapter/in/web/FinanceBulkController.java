package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.BulkFinanceRegisterRequest;
import com.kista.adapter.in.web.dto.BulkFinanceRegisterResponse;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.BulkFinanceRegisterResult;
import com.kista.domain.model.finance.FinanceTransactionCommand;
import com.kista.domain.port.in.BulkFinanceRegisterUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance/bulk-register")
@RequiredArgsConstructor
public class FinanceBulkController {

    private final BulkFinanceRegisterUseCase bulkFinanceRegisterUseCase;

    @Operation(summary = "가계부 일괄 등록", description = "소스월 자산/수입/소비/저축 기록을 대상월로 한 번에 복제 등록합니다.")
    @ApiResponse(responseCode = "200", description = "등록 처리 완료 (항목별 성공/실패는 응답 본문 참고)")
    @PostMapping
    public BulkFinanceRegisterResponse register(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) UUID groupId,
            @Valid @RequestBody BulkFinanceRegisterRequest request) {
        List<AssetSnapshotCommand> assets = request.assets() == null ? List.of() : request.assets().stream()
                .map(BulkFinanceRegisterRequest.AssetItem::toCommand).toList();
        List<FinanceTransactionCommand> transactions = request.transactions() == null ? List.of() : request.transactions().stream()
                .map(BulkFinanceRegisterRequest.TransactionItem::toCommand).toList();
        BulkFinanceRegisterResult result = bulkFinanceRegisterUseCase.register(userId, groupId, assets, transactions);
        return BulkFinanceRegisterResponse.from(result);
    }
}
