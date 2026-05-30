package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/trades")
@RequiredArgsConstructor
public class AdminTradeController {

    private final AdminListTradesUseCase listTrades;      // 최근 30일 전체 거래 내역
    private final AdminListAccountsUseCase listAccounts;  // accountId → userId 매핑용
    private final AdminListUsersUseCase listUsers;        // userId → nickname 매핑용

    // 전체 거래 내역 목록 — 일괄 조회로 N+1 방지
    @GetMapping
    public List<AdminTradeResponse> listTrades() {
        // 계좌·사용자 맵 일괄 생성
        Map<UUID, Account> accountMap = listAccounts.listAll().stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        Map<UUID, AdminUserView> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));
        return listTrades.listAll().stream()
                .map(t -> AdminTradeResponse.from(t, accountMap, userMap))
                .toList();
    }

    // 거래 내역 응답 DTO — package-private record (같은 패키지 AdminAnomaliesController에서 재사용 가능)
    record AdminTradeResponse(
            UUID id,
            UUID userId,
            String ownerNickname,    // 계좌 소유자 닉네임
            LocalDate tradeDate,
            String ticker,
            String direction,        // BUY | SELL
            String orderType,        // LOC | MOC | LIMIT
            int quantity,
            BigDecimal price,
            String status            // PLACED | FILLED | FAILED
    ) {
        static AdminTradeResponse from(Order t, Map<UUID, Account> accountMap, Map<UUID, AdminUserView> userMap) {
            // accountId → userId → nickname 순서로 역방향 조회
            Account account = t.accountId() != null ? accountMap.get(t.accountId()) : null;
            UUID userId = account != null ? account.userId() : null;
            AdminUserView user = userId != null ? userMap.get(userId) : null;
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            return new AdminTradeResponse(
                    t.id(), userId, nickname, t.tradeDate(), t.ticker().name(),
                    t.direction().name(), t.orderType().name(),
                    t.quantity(), t.price(), t.status().name());
        }
    }
}
