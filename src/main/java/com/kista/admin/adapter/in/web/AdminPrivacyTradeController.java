package com.kista.admin.adapter.in.web;

import com.kista.admin.adapter.in.web.dto.AdminPrivacyBaseResponse;
import com.kista.admin.application.usecase.AdminPrivacyTradeUseCase;
import com.kista.admin.application.usecase.AdminQueryUseCase;
import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyBaseUpdateCommand;
import com.kista.privacy.domain.model.PrivacyOrderUpdateCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/privacy-trade-bases")
@RequiredArgsConstructor
public class AdminPrivacyTradeController {

    private final AdminQueryUseCase adminQuery;
    private final AdminPrivacyTradeUseCase adminPrivacyTrade;

    // PRIVACY 기준 매매표(master) + 주문 명세(detail) 목록 — range 미전달 시 전체, 최소 30
    @Operation(summary = "PRIVACY 기준 매매표 목록 조회", description = "기준 매매표(master)와 주문 명세(detail) 목록을 반환합니다. range 미전달 시 전체 조회, 전달 시 최소 30 이상이어야 합니다.")
    @GetMapping
    public List<AdminPrivacyBaseResponse> listBases(@RequestParam(required = false) Integer range) {
        if (range != null && range < 30)
            throw new IllegalArgumentException("range는 30 이상이어야 합니다");
        return adminQuery.listPrivacyBases(range).stream()
                .map(AdminPrivacyBaseResponse::from)
                .toList();
    }

    // FIDA 오류 대응 수동 등록 — executeFidaOrder와 동일 멱등 규칙(동일 내용 200, 다른 내용 409)
    @Operation(summary = "PRIVACY 기준 매매표 수동 등록", description = "FIDA 수신 실패 등 오류 대응용. 동일 (releaseDate, ticker) 존재 시 내용이 같으면 200(멱등), 다르면 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "신규 등록 성공"),
            @ApiResponse(responseCode = "200", description = "기존 동일 데이터 존재 — 멱등 처리"),
            @ApiResponse(responseCode = "409", description = "같은 날짜/종목에 내용이 다른 데이터 존재")
    })
    @PostMapping
    public ResponseEntity<AdminPrivacyBaseResponse> createBase(
            @AuthenticationPrincipal UUID adminId,
            @RequestBody @Valid FidaOrderCommand command) {
        AdminPrivacyTradeUseCase.CreateResult result = adminPrivacyTrade.createBase(adminId, command);
        AdminPrivacyBaseResponse body = AdminPrivacyBaseResponse.from(result.view());
        if (!result.created()) return ResponseEntity.ok(body);
        return ResponseEntity.created(URI.create("/api/admin/privacy-trade-bases/" + result.view().id())).body(body);
    }

    // 마스터 필드(기준가·실현손익·평단가·보유수량) 전체 교체
    @Operation(summary = "PRIVACY 기준 매매표 마스터 수정")
    @PatchMapping("/{id}")
    public AdminPrivacyBaseResponse updateBase(
            @AuthenticationPrincipal UUID adminId,
            @PathVariable UUID id,
            @RequestBody @Valid PrivacyBaseUpdateCommand command) {
        return AdminPrivacyBaseResponse.from(adminPrivacyTrade.updateBase(adminId, id, command));
    }

    // 개별 주문 명세 가격·수량 수정
    @Operation(summary = "PRIVACY 주문 명세 수정")
    @PatchMapping("/{baseId}/orders/{orderId}")
    public AdminPrivacyBaseResponse updateOrder(
            @AuthenticationPrincipal UUID adminId,
            @PathVariable UUID baseId,
            @PathVariable UUID orderId,
            @RequestBody @Valid PrivacyOrderUpdateCommand command) {
        return AdminPrivacyBaseResponse.from(adminPrivacyTrade.updateOrder(adminId, baseId, orderId, command));
    }

}
