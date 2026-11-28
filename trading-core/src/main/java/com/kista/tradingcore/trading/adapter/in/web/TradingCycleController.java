package com.kista.tradingcore.trading.adapter.in.web;

import com.kista.tradingcore.trading.adapter.in.web.dto.*;
import com.kista.tradingcore.trading.application.service.DcaConfigService;
import com.kista.tradingcore.trading.application.service.DcaExecutionQueryService;
import com.kista.tradingcore.trading.domain.model.CycleHistoryPage;
import com.kista.tradingcore.trading.domain.model.DcaExecutionFilter;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.platform.page.CursorPage;
import com.kista.shared.platform.page.PageQuery;
import com.kista.tradingcore.trading.application.service.CapitalLedgerService;
import com.kista.tradingcore.trading.application.service.CapitalWithdrawalService;
import com.kista.tradingcore.trading.application.service.StrategyService;
import com.kista.tradingcore.trading.application.service.TradingExecutionFacade;
import com.kista.tradingcore.trading.application.service.VrReconfigureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;

@Tag(name = "거래 사이클", description = "계좌별 매매 사이클 등록·조회·수정·삭제·일시중지·재개")
@RestController
@RequiredArgsConstructor
public class TradingCycleController {

    private final StrategyService tradingCycle;                  // CRUD + pause/resume + 이력·주문·시드 미리보기
    private final TradingExecutionFacade tradingExecution;      // 주문 취소
    private final VrReconfigureService vrReconfigure;            // VR 전략 운영 중 재설정
    private final CapitalLedgerService injectCapital;           // PRIVACY·INFINITE·DCA 진행 중 사이클 투자금 추가
    private final CapitalWithdrawalService withdrawCapital;       // PRIVACY·INFINITE·DCA 진행 중 사이클 투자금 인출
    private final DcaConfigService dcaConfig;                    // DCA 설정 수정
    private final DcaExecutionQueryService dcaExecutionQueryService;           // DCA 회차 이력 조회
    private final Clock clock;                                   // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    // 로그인 사용자의 전 계좌 전략 목록 — 모바일 전략 탭용
    @Operation(summary = "내 전체 거래 사이클 목록")
    @GetMapping("/api/trading-cycles")
    public List<TradingCycleResponse> listMine(@AuthenticationPrincipal UUID userId,
                                                @RequestParam(defaultValue = "false") boolean includeArchived) {
        return tradingCycle.listByUserId(userId, includeArchived).stream()
                .map(TradingCycleResponse::from)
                .toList();
    }

    // 계좌의 거래 사이클 목록 조회
    @Operation(summary = "거래 사이클 목록 조회")
    @GetMapping("/api/accounts/{accountId}/trading-cycles")
    public List<TradingCycleResponse> list(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return tradingCycle.listByAccountId(accountId, userId).stream()
                .map(TradingCycleResponse::from)
                .toList();
    }

    // 거래 사이클 등록
    @Operation(summary = "거래 사이클 등록")
    @PostMapping("/api/accounts/{accountId}/trading-cycles")
    @ResponseStatus(HttpStatus.CREATED) // OpenAPI 문서용 — springdoc은 ResponseEntity.created()에서 201을 추론하지 못해 200으로 적는다(kista-ui api-types 계약)
    public ResponseEntity<TradingCycleResponse> register(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody TradingCycleRequest request) {
        TradingCycleResponse body = TradingCycleResponse.forRegistration(
                tradingCycle.register(userId, accountId, request.toRegisterCommand()), ZonedDateTime.now(clock));
        // 201 + 신규 리소스 URI
        return ResponseEntity.created(URI.create("/api/trading-cycles/" + body.id())).body(body);
    }

    // 거래 사이클 단건 조회 — 등록 201 Location 대상
    @Operation(summary = "거래 사이클 단건 조회")
    @GetMapping("/api/trading-cycles/{id}")
    public TradingCycleResponse get(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return TradingCycleResponse.from(tradingCycle.getById(id, userId));
    }

    // 거래 사이클 수정 (cycleSeedType 등 메타 변경)
    @Operation(summary = "거래 사이클 수정")
    @PutMapping("/api/trading-cycles/{id}")
    public TradingCycleResponse update(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestBody TradingCycleRequest request) {
        return TradingCycleResponse.from(
                tradingCycle.update(id, userId, request.toUpdateCommand())
        );
    }

    // VR 전략 운영 중 재설정 — 새 버전 발급 + 강제 롤오버(파라미터 수정·투자금 추가/인출)
    @Operation(summary = "VR 전략 운영 중 재설정")
    @PutMapping("/api/trading-cycles/{id}/vr-config")
    public TradingCycleResponse reconfigureVr(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody VrConfigRequest request) {
        vrReconfigure.reconfigure(id, userId, request.toCommand());
        return TradingCycleResponse.from(tradingCycle.getById(id, userId));
    }

    // 등록 검증 전용 — 등록과 같은 body로 저장 없이 해석·검증만, 통과·거절 모두 200
    @Operation(summary = "거래 사이클 등록 검증", description = "등록과 같은 해석·검증 경로를 저장 없이 태운다. 등록이 400·409로 거절할 입력은 fieldErrors로 돌려준다(소유권 실패는 403/404)")
    @PostMapping("/api/accounts/{accountId}/trading-cycle-validations")
    public StrategyValidationResponse validateRegistration(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody TradingCycleRequest request) {
        return StrategyValidationResponse.from(tradingCycle.validateRegistration(userId, accountId, request.toRegisterCommand()));
    }

    // 수정 검증 전용 — PUT /api/trading-cycles/{id}와 같은 body
    @Operation(summary = "거래 사이클 수정 검증")
    @PostMapping("/api/trading-cycles/{id}/update-validations")
    public StrategyValidationResponse validateUpdate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestBody TradingCycleRequest request) {
        return StrategyValidationResponse.from(tradingCycle.validateUpdate(id, userId, request.toUpdateCommand()));
    }

    // VR 재설정 검증 전용 — PUT /api/trading-cycles/{id}/vr-config와 같은 body, 현재가 조회 외 외부 호출·저장 없음
    @Operation(summary = "VR 재설정 검증")
    @PostMapping("/api/trading-cycles/{id}/vr-config-validations")
    public StrategyValidationResponse validateVrConfig(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody VrConfigRequest request) {
        return StrategyValidationResponse.from(vrReconfigure.validate(id, userId, request.toCommand()));
    }

    // 진행 중 PRIVACY·INFINITE·DCA 사이클 투자금 추가 — 사이클 유지, 포지션 스냅샷 append
    @Operation(summary = "진행 중 사이클 투자금 추가")
    @PostMapping("/api/trading-cycles/{id}/capital-injection")
    public TradingCycleResponse injectCapital(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CapitalInjectionRequest request) {
        injectCapital.inject(id, userId, request.toCommand());
        return TradingCycleResponse.from(tradingCycle.getById(id, userId));
    }

    // 진행 중 PRIVACY·INFINITE·DCA 사이클 투자금 인출 — 장부 예수금·startAmount 차감(브로커 호출 없음)
    @Operation(summary = "진행 중 사이클 투자금 인출")
    @PostMapping("/api/trading-cycles/{id}/capital-withdrawal")
    public TradingCycleResponse withdrawCapital(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CapitalWithdrawalRequest request) {
        withdrawCapital.withdraw(id, userId, request.toCommand());
        return TradingCycleResponse.from(tradingCycle.getById(id, userId));
    }

    // DCA 설정 수정 — 새 버전 발급, 사이클 유지. 종목은 변경 불가(요청에 없음), 규칙별 방향 변경은 허용
    @Operation(summary = "DCA 설정 수정")
    @PutMapping("/api/trading-cycles/{id}/dca-config")
    public TradingCycleResponse reconfigureDca(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody DcaConfigRequest request) {
        dcaConfig.updateConfig(id, userId, request.toDomain());
        return TradingCycleResponse.from(tradingCycle.getById(id, userId));
    }

    // 거래 사이클 삭제
    @Operation(summary = "거래 사이클 삭제", description = "미체결 주문을 증권사에 취소한 뒤 전략·사이클·포지션을 삭제 처리(소프트 삭제). 취소에 실패한 주문이 있으면 400으로 중단.")
    @DeleteMapping("/api/trading-cycles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.delete(id, userId);
    }

    // 거래 사이클 일시중지 (ACTIVE → PAUSED)
    @Operation(summary = "거래 사이클 일시중지", description = "오늘 미체결 주문을 정리(PLANNED 삭제·PLACED 증권사 취소)한 뒤 PAUSED로 바꾼다. 취소 실패분은 관리자에게 알리고 일시중지는 진행.")
    @PatchMapping("/api/trading-cycles/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.pause(id, userId);
    }

    // 거래 사이클 보관 (→ ARCHIVED, 해제 없음)
    @Operation(summary = "전략 보관", description = "보유 수량이 없고 당일 접수 주문이 마감 반영된 전략만 보관한다. 미체결 주문을 정리한 뒤 ARCHIVED로 바꾸며 되돌릴 수 없다.")
    @PatchMapping("/api/trading-cycles/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.archive(id, userId);
    }

    // 거래 사이클 재개 (PAUSED → ACTIVE)
    @Operation(summary = "거래 사이클 재개")
    @PatchMapping("/api/trading-cycles/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        tradingCycle.resume(id, userId);
    }

    // 오늘 등록된 사이클 주문 전체 취소 — PLANNED 삭제 + PLACED 증권사 취소 (best-effort)
    @Operation(summary = "사이클 주문 전체 취소 (오늘 PLANNED + PLACED 주문 전체)")
    @DeleteMapping("/api/trading-cycles/{id}/execute")
    public CancelOrdersResponse cancelExecute(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return CancelOrdersResponse.from(tradingExecution.cancelByCycle(id, userId));
    }

    // 전략(사이클) 기준 다음 주문 미리보기 — DB 저장 없음, 휴장·상태 무관 강제 계산
    @Operation(summary = "전략 다음 주문 미리보기")
    @GetMapping("/api/trading-cycles/{id}/preview")
    public NextOrdersResponse preview(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return NextOrdersResponse.from(tradingExecution.preview(id, userId));
    }

    // 계좌 내 전략 전체 다음 주문 미리보기 일괄 조회 — 전략 목록 화면에서 전략별 N회 호출 대신 1회로 축소
    @Operation(summary = "계좌 내 전략 전체 다음 주문 미리보기 일괄 조회")
    @GetMapping("/api/accounts/{accountId}/trading-cycles/previews")
    public Map<UUID, NextOrdersResponse> previewBatch(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return tradingExecution.previewBatch(accountId, userId).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> NextOrdersResponse.from(e.getValue())));
    }

    // 전략 등록/수정 폼용 최소시드·기준가 미리보기
    @Operation(summary = "전략 최소 투자금·기준가 미리보기")
    @GetMapping("/api/accounts/{accountId}/strategy-seed-preview")
    public StrategySeedPreviewResponse seedPreview(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @RequestParam StrategyType type,
            @RequestParam StrategyTicker ticker,
            @RequestParam(defaultValue = "20") int divisionCount) {
        return StrategySeedPreviewResponse.from(
                tradingCycle.strategySeedPreview(accountId, userId, type, ticker, divisionCount));
    }

    // 전략(사이클) 기준 거래 이력 조회 — 커서 기반 페이지네이션
    @Operation(summary = "전략 거래 이력 조회", description = "[페이지형] 전략(사이클) 기준 거래 이력, 최신순.")
    @GetMapping("/api/trading-cycles/{id}/history")
    public CursorPage<CycleHistoryResponse> getStrategyHistory(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        // size 기본값·상한은 PageQuery가 정한다
        PageQuery query = PageQuery.of(cursor, size);
        Instant cursorInstant = query.cursor() != null ? Instant.parse(query.cursor()) : null;
        CycleHistoryPage page = tradingCycle.getByStrategy(id, userId, from, to, cursorInstant, query.size());
        return new CursorPage<>(page.items().stream().map(CycleHistoryResponse::from).toList(),
                page.nextCursor() != null ? page.nextCursor().toString() : null, page.hasMore());
    }

    // 전략(사이클) 기준 주문 내역 조회 — 사용자 전략 상세 화면용
    @Operation(summary = "전략 주문 내역 조회")
    @GetMapping("/api/trading-cycles/{id}/orders")
    public StrategyOrdersResponse getStrategyOrders(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate resolvedFrom = from != null ? from : today.minusDays(30);
        LocalDate resolvedTo = to != null ? to : today;
        return StrategyOrdersResponse.from(
                tradingCycle.getOrdersByStrategy(id, userId, resolvedFrom, resolvedTo));
    }

    // DCA 회차 이력 — 거래일 DESC 페이지 (미주문 사유 포함)
    @Operation(summary = "DCA 회차 이력 조회")
    @GetMapping("/api/trading-cycles/{id}/dca-executions")
    public DcaExecutionPageResponse listDcaExecutions(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) OrderDirection direction,
            @RequestParam(required = false) String ruleKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        DcaExecutionFilter filter = new DcaExecutionFilter(from, to, direction, ruleKey);
        return DcaExecutionPageResponse.from(dcaExecutionQueryService.list(id, userId, filter, page, size));
    }
}
