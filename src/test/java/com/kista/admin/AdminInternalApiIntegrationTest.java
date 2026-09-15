package com.kista.admin;

import com.kista.sharedkernel.OrderStatus;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.application.usecase.AdminReorderUseCase;
import com.kista.admin.application.usecase.AdminTradeCorrectionUseCase;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.TimeZones;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// admin의 reorder/trade-correction이 실제로 /api/internal/trading/** HTTP 왕복을 거쳐 trading-core에 도달하는지 검증
// (mocked port 아님) — MOCK 브로커 계좌 + 실 DB 픽스처로 전체 Spring 컨텍스트를 띄운다.
// 서버가 자기 자신을 호출하므로 internal.api.base-url이 실제 리스닝 포트를 가리켜야 한다 — RANDOM_PORT는
// InternalApiClientConfig의 @ConfigurationProperties가 bean 생성 시점(웹서버 기동 전)에 base-url을 eager
// 바인딩해 "${local.server.port}" 치환이 성립하지 않는다(DynamicPropertySource로 실측 확인). 고정 포트로 우회.
//
// 4a단계(패키징 분리)로 root(KistaApplication)와 trading-core(TradingApplication)가 서로 다른 부트
// 진입점을 가진 완전히 별도 프로세스가 되면서 이 테스트의 전제(한 JVM/한 Spring 컨텍스트 안에 admin과
// trading-core 빈이 함께 떠서 자기 자신을 호출) 자체가 성립하지 않는다 — root의 scanBasePackages가
// trading-core 패키지(com.kista.account 등)를 의도적으로 배제하도록 고쳐졌기 때문(빈 중복 등록 버그
// 수정, 이 자체가 4a의 목표). 진짜 프로세스 간 HTTP 왕복 검증은 4a 최종 게이트의 로컬 2-프로세스
// 스모크 테스트(수동)로 대체된다 — 이 테스트를 2-프로세스 방식으로 재작성하는 건 별도 작업 범위.
@Disabled("4a단계로 root/trading-core가 별도 프로세스가 되며 단일 JVM 자가호출 전제가 깨짐 — 2-프로세스 스모크 테스트로 대체 예정")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18391",
                "internal.api.base-url=http://localhost:18391",
                "internal.api.token=integration-test-token"
        })
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 + 고정 포트 — 병렬 실행 시 트랜잭션/포트 충돌 방지 관례
class AdminInternalApiIntegrationTest {

    @Autowired private AdminReorderUseCase adminReorderUseCase;
    @Autowired private AdminTradeCorrectionUseCase adminTradeCorrectionUseCase;
    @Autowired private TradingCommandPort tradingCommandPort; // reorder 가용성 실측 + 배선 확인용
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AccountPort accountPort; // account_no/app_key/secret_key는 AES-GCM 암호화 컬럼이라 raw INSERT 불가 — 실 어댑터로 생성

    // 서버 자기호출 HTTP 라운드트립이라 테스트 트랜잭션 롤백이 적용되지 않는다 —
    // 생성한 user를 지우면 accounts/strategy/strategy_version/strategy_cycle/cycle_position/orders가
    // 전부 ON DELETE CASCADE로 함께 정리된다.
    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE admin_id = ?", userId); // admin_id는 ON DELETE SET NULL이라 명시 정리
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void MOCK_계좌_재주문이_내부_API를_왕복해_PLANNED로_접수된다() {
        // 재주문 접수는 실시간 KST 요일/시각(DstInfo)에 무조건 게이팅된다(BLOCKED 시간대·주말 전부 불가) —
        // 테스트 클럭을 주입할 수 없으므로 실제 가용성을 먼저 조회(=배선 검증 겸용)하고, 불가 구간이면 스킵한다.
        AdminReorderTimingAvailability avail = tradingCommandPort.reorderTimingAvailability();
        assumeTrue(avail.atClose(), "BLOCKED 시간대 또는 주말 — 지금은 재주문 접수가 불가능한 구간이라 스킵");

        UUID userId = UUID.randomUUID();
        createdUserIds.add(userId);
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        LocalDate today = LocalDate.now(TimeZones.KST);

        seedMarketHoliday(today); // 휴장일 캘린더 데이터 없으면 isMarketOpen()이 무조건 폐장으로 폴백

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, 'ACTIVE', 'USER', now(), now())",
                userId, "kakao_" + userId);
        UUID accountId = insertMockAccount(userId);
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) "
                        + "VALUES (?, ?, 'INFINITE', 'SOXL', 'ACTIVE', 'NONE', now(), now())",
                strategyId, accountId);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, 1, now())",
                versionId, strategyId);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), today);
        jdbcTemplate.update(
                "INSERT INTO orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, price, quantity, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SOXL', 'LOC', 'AT_CLOSE', 'BUY', ?, ?, 'PLANNED', now(), now())",
                orderId, accountId, cycleId, today, new BigDecimal("20.00"), 1);

        AdminReorderCommand command = new AdminReorderCommand(
                userId, accountId, strategyId, orderId,
                OrderTiming.AT_CLOSE, today, OrderDirection.BUY, 2, new BigDecimal("21.00"), "통합테스트");

        AdminReorderResult result = adminReorderUseCase.reorder(userId, command); // adminId는 감사 로그 FK용 — 소유자 재사용

        assertThat(result.sourceOrderId()).isEqualTo(orderId);
        assertThat(result.resultingStatus()).isEqualTo(OrderStatus.PLANNED);

        // 실제 HTTP 왕복이 DB까지 반영됐는지 — 원본 주문 취소 + 신규 PLANNED 주문 생성
        Integer cancelledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE id = ? AND status = 'CANCELLED'", Integer.class, orderId);
        assertThat(cancelledCount).isEqualTo(1);

        Integer newPlannedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE strategy_cycle_id = ? AND status = 'PLANNED' AND id != ? AND quantity = 2",
                Integer.class, cycleId, orderId);
        assertThat(newPlannedCount).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE admin_id = ? AND action = 'REORDER' AND target_id = ?",
                Integer.class, userId, orderId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void MOCK_계좌_수동_체결_보정이_내부_API를_왕복해_잔고에_반영된다() {
        // 시계·시장 캘린더 의존이 전혀 없는 순수 DB 경로 — 요일/시각과 무관하게 항상 실행된다
        UUID userId = UUID.randomUUID();
        createdUserIds.add(userId);
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        LocalDate today = LocalDate.now(TimeZones.KST);

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, 'ACTIVE', 'USER', now(), now())",
                userId, "kakao_" + userId);
        UUID accountId = insertMockAccount(userId);
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) "
                        + "VALUES (?, ?, 'INFINITE', 'SOXL', 'ACTIVE', 'NONE', now(), now())",
                strategyId, accountId);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, 1, now())",
                versionId, strategyId);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), today);
        // 개장 포지션 스냅샷: holdings=2, avgPrice=100.00, usdDeposit=1000.00
        jdbcTemplate.update(
                "INSERT INTO cycle_position (id, strategy_cycle_id, usd_deposit, avg_price, holdings, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), cycleId, new BigDecimal("1000.00"), new BigDecimal("100.00"), 2);

        AdminManualTradeCorrectionCommand command = new AdminManualTradeCorrectionCommand(
                userId, accountId, strategyId,
                List.of(new AdminManualTradeCorrectionCommand.Fill(
                        today, OrderDirection.BUY, 1, new BigDecimal("120.00"), "MANUAL-1", "통합테스트")));

        AdminTradeCorrectionResult result = adminTradeCorrectionUseCase.correctManualFills(userId, command);

        // BUY 1주@120 반영 후: holdings=3, avgPrice=(100*2+120)/3=106.6667, usdDeposit=1000-120=880.00
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.finalHoldings()).isEqualTo(3);
        assertThat(result.finalAvgPrice()).isEqualByComparingTo("106.6667");
        assertThat(result.finalUsdDeposit()).isEqualByComparingTo("880.00");
        assertThat(result.cycleEnded()).isFalse();

        // 실제 HTTP 왕복이 DB까지 반영됐는지 — 포지션 스냅샷 append + FILLED 주문 이력 생성
        Integer positionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cycle_position WHERE strategy_cycle_id = ?", Integer.class, cycleId);
        assertThat(positionRows).isEqualTo(2);

        Integer filledOrderRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE strategy_cycle_id = ? AND status = 'FILLED' AND external_order_id = ?",
                Integer.class, cycleId, "MANUAL-1");
        assertThat(filledOrderRows).isEqualTo(1);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE admin_id = ? AND action = 'TRADE_MANUAL_CORRECTION' AND target_id = ?",
                Integer.class, userId, strategyId);
        assertThat(auditCount).isEqualTo(1);
    }

    // account_no/app_key/secret_key는 AES-256-GCM 암호화 컬럼이라 raw INSERT로 평문을 넣으면
    // 조회 시 복호화 실패(IllegalArgumentException)로 터진다 — 실제 AccountPort로 생성해 암호화를 정상 통과시킨다
    private UUID insertMockAccount(UUID userId) {
        Account saved = accountPort.save(new Account(null, userId, "MOCK테스트계좌",
                "MOCKACC-" + UUID.randomUUID().toString().substring(0, 8), "key", "secret",
                null, Broker.MOCK, null));
        return saved.id();
    }

    // isMarketOpen()이 휴장일 캘린더 데이터 부재 시 무조건 폐장으로 폴백하므로(MarketCalendarPersistenceAdapter),
    // 오늘 US 거래일이 속한 연도에 최소 1건은 있어야 한다. 실제 오늘 날짜와 겹치지 않는 임의 날짜를 사용.
    private void seedMarketHoliday(LocalDate todayKst) {
        LocalDate usDate = todayKst.minusDays(1); // UsTradeDates.toUsTradeDate와 동일 규칙(KST-1일)
        LocalDate marker = usDate.getMonthValue() == 12 && usDate.getDayOfMonth() == 31
                ? LocalDate.of(usDate.getYear(), 1, 1)
                : LocalDate.of(usDate.getYear(), 12, 31);
        jdbcTemplate.update("INSERT INTO us_market_holidays (trade_date) VALUES (?) ON CONFLICT (trade_date) DO NOTHING", marker);
    }
}

