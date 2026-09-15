package com.kista.account.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// admin의 AccountQueryHttpAdapter가 소비하는 내부 전용 읽기 엔드포인트 — X-Internal-Token 인증.
// Account를 그대로 반환하지 않는다 — appKey/secretKey(복호화된 브로커 자격증명)까지 내부망으로
// 직렬화되는 것을 막기 위해 admin의 AdminAccountView와 byte-identical한 5필드 응답 own-type을 쓴다
// (trading Order/Strategy own-type들과 달리 Account는 비밀값을 담고 있어 전체 반환 관례를 따르지 않는다).
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/accounts")
@RequiredArgsConstructor
public class AccountInternalController {

    private final AccountPort accountPort;

    // admin AdminAccountView와 필드명·타입 byte-identical — Jackson 매핑 없이 역직렬화되는 전제
    record AccountInternalResponse(UUID id, UUID userId, String accountNo, Broker broker, Instant createdAt) {
        static AccountInternalResponse from(Account a) {
            return new AccountInternalResponse(a.id(), a.userId(), a.accountNo(), a.broker(), a.createdAt());
        }
    }

    @Operation(summary = "전체 계좌 조회", description = "관리자 계좌 목록 조회용. X-Internal-Token 필수. from/to 미지정 시 전체, 지정 시 createdAt(KST) 기준 필터링.")
    @GetMapping
    public List<AccountInternalResponse> listAccounts(
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
                .map(AccountInternalResponse::from)
                .toList();
    }

    @Operation(summary = "계좌 단건 조회", description = "없으면 404.")
    @GetMapping("/{id}")
    public AccountInternalResponse findAccount(@PathVariable UUID id) {
        return AccountInternalResponse.from(accountPort.findByIdOrThrow(id));
    }

    // 관리자 대시보드 통계(AdminQueryService.getStats())용 — 전체 계좌 수
    @Operation(summary = "전체 계좌 수 조회", description = "관리자 대시보드 통계용. X-Internal-Token 필수.")
    @GetMapping("/count")
    public long count() {
        return accountPort.countAll();
    }
}
