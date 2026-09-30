package com.kista.privacy.adapter.in.web;

import com.kista.contract.privacy.PrivacyBaseUpdateRequest;
import com.kista.contract.privacy.PrivacyOrderAddRequest;
import com.kista.contract.privacy.PrivacyOrderUpdateRequest;
import com.kista.contract.privacy.PrivacyTradeBaseResponse;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// admin의 PrivacyQueryHttpAdapter가 소비하는 내부 전용 마스터/주문 명세 조회·수정 엔드포인트 — X-Internal-Token 인증.
// 기존 PrivacyInternalQueryController(목록 조회)와 별개 — 단건 조회+쓰기 경로만 담당한다.
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/privacy/trade-bases")
@RequiredArgsConstructor
public class PrivacyBaseInternalController {

    private final PrivacyTradePort privacyTradePort;

    // admin의 createBase 완료 후 order id 포함 전체 view를 재조회하기 위한 단건 조회
    @Operation(summary = "기준 매매표 단건 조회", description = "admin createBase 후속 재조회 전용. X-Internal-Token 필수.")
    @GetMapping("/{baseId}")
    public PrivacyTradeBaseResponse findById(@PathVariable UUID baseId) {
        return PrivacyContractMapper.toResponse(privacyTradePort.findByIdOrThrow(baseId));
    }

    @Operation(summary = "기준 매매표 마스터 수정", description = "관리자 수동 보정 전용. X-Internal-Token 필수.")
    @PatchMapping("/{baseId}")
    public PrivacyTradeBaseResponse updateBase(@PathVariable UUID baseId, @RequestBody @Valid PrivacyBaseUpdateRequest request) {
        return PrivacyContractMapper.toResponse(privacyTradePort.updateBase(baseId, PrivacyContractMapper.toCommand(request)));
    }

    @Operation(summary = "주문 명세 수정", description = "관리자 수동 보정 전용. X-Internal-Token 필수.")
    @PatchMapping("/{baseId}/orders/{orderId}")
    public PrivacyTradeBaseResponse updateOrder(@PathVariable UUID baseId, @PathVariable UUID orderId,
                                                 @RequestBody @Valid PrivacyOrderUpdateRequest request) {
        return PrivacyContractMapper.toResponse(
                privacyTradePort.updateOrder(baseId, orderId, PrivacyContractMapper.toCommand(request)));
    }

    @Operation(summary = "주문 명세 추가", description = "관리자 수동 보정 전용. X-Internal-Token 필수.")
    @PostMapping("/{baseId}/orders")
    public PrivacyTradeBaseResponse addOrder(@PathVariable UUID baseId, @RequestBody @Valid PrivacyOrderAddRequest request) {
        return PrivacyContractMapper.toResponse(privacyTradePort.addOrder(baseId, PrivacyContractMapper.toCommand(request)));
    }

    @Operation(summary = "주문 명세 삭제", description = "관리자 수동 보정 전용. 마지막 1건은 삭제 불가(400). X-Internal-Token 필수.")
    @DeleteMapping("/{baseId}/orders/{orderId}")
    public PrivacyTradeBaseResponse deleteOrder(@PathVariable UUID baseId, @PathVariable UUID orderId) {
        return PrivacyContractMapper.toResponse(privacyTradePort.deleteOrder(baseId, orderId));
    }
}
