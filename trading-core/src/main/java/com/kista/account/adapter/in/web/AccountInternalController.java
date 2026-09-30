package com.kista.account.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.contract.account.AccountSummaryResponse;
import com.kista.sharedkernel.TimeZones;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// admin의 AccountQueryHttpAdapter가 소비하는 내부 전용 읽기 엔드포인트 — X-Internal-Token 인증.
// Account를 그대로 반환하지 않는다 — appKey/secretKey(복호화된 브로커 자격증명)까지 내부망으로
// 직렬화되는 것을 막기 위해 5필드로 좁힌 contract 응답(AccountSummaryResponse)으로 매핑한다.
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/accounts")
@RequiredArgsConstructor
public class AccountInternalController {

    private final AccountPort accountPort;

    // Account → contract 응답 — 자격증명 필드는 매핑하지 않는다
    private static AccountSummaryResponse toResponse(Account a) {
        return new AccountSummaryResponse(a.id(), a.userId(), a.accountNo(), a.broker(), a.createdAt());
    }

    @Operation(summary = "전체 계좌 조회", description = "관리자 계좌 목록 조회용. X-Internal-Token 필수. from/to 미지정 시 전체, 지정 시 createdAt(KST) 기준 필터링.")
    @GetMapping
    public List<AccountSummaryResponse> listAccounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<Account> all = accountPort.findAll();
        return all.stream()
                .filter(a -> {
                    if (from == null && to == null) return true;
                    if (a.createdAt() == null) return true;
                    LocalDate d = a.createdAt().atZone(TimeZones.KST).toLocalDate();
                    return (from == null || !d.isBefore(from))
                        && (to   == null || !d.isAfter(to));
                })
                .map(AccountInternalController::toResponse)
                .toList();
    }

    @Operation(summary = "계좌 단건 조회", description = "없으면 404.")
    @GetMapping("/{id}")
    public AccountSummaryResponse findAccount(@PathVariable UUID id) {
        return toResponse(accountPort.findByIdOrThrow(id));
    }

    // 관리자 대시보드 통계(AdminQueryService.getStats())용 — 전체 계좌 수
    @Operation(summary = "전체 계좌 수 조회", description = "관리자 대시보드 통계용. X-Internal-Token 필수.")
    @GetMapping("/count")
    public long count() {
        return accountPort.countAll();
    }
}
