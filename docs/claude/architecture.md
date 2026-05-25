## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).

```
domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체 (record 사용), 어그리게이트별 서브패키지
    user/        ← User, UserRole, UserStatus
    account/     ← Account(+nested exceptions: CooldownException, InvalidKisKeyException), Broker
    tradingcycle/ ← TradingCycle(+nested: Type, Status, Ticker), TradingCycleHistory  ← **V35: 구 Strategy 대체**
                   import 경로: com.kista.domain.model.tradingcycle.TradingCycle.Ticker (구 Strategy.Ticker 아님)
                   필드: id, accountId, type(Type), status(Status), ticker(Ticker), multiple, initialUsdDeposit, createdAt, updatedAt
    strategy/    ← InfinitePosition, AccountBalance, TradingSnapshot, TradingReport, DstInfo (Ticker/StrategyType은 TradingCycle nested enum으로 이동)
    order/       ← Order, TradeHistory, TradeEvent, PortfolioSnapshot, ReservationOrderCommand
    kis/         ← KIS 응답 record (Execution, PresentBalanceResult, PeriodProfitResult, DailyTransaction* 등)
    admin/       ← AdminAnomalies, AdminStats, AuditLog
  strategy/      ← TradingStrategy 인터페이스 및 구현 (InfiniteStrategy) — @Component 허용 예외 (ArchUnit)
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스

application/
  service/       ← UseCase 구현체 (@Service), Port를 통해서만 외부 호출

adapter/in/
  schedule/      ← TradingScheduler (월~금 04:00 KST, 멀티계좌)
  web/           ← REST Controller + DTO
                    - AccountController: 계좌 CRUD (POST/GET/PUT/DELETE /api/accounts)
                    - TradingCycleController: 사이클 CRUD + pause/resume (GET/POST /api/accounts/{id}/trading-cycles, PUT/DELETE/PATCH /api/trading-cycles/{id})
                    - MetaController: enum 메타데이터 SSOT (GET /api/meta/**) — UI 라벨/설명/available tickers. Cache-Control max-age=1h
                    - OrderController: 예약주문(POST /reservation-orders) + 다음 주문 미리보기(GET /orders/next)
                    - FidaOrderController: 서버 간 내부 주문 수신 (POST /api/internal/fida-orders, X-Internal-Token 인증)
  web/security/  ← JwtAuthFilter (Bearer JWT), InternalTokenAuthFilter (X-Internal-Token 서버간 인증)
  telegram/      ← TelegramWebhookController + TelegramBotService

### DashboardController vs StatisticsController 응답 형식 차이
- `DashboardController`: DB 기반 → `PortfolioSnapshotResponse`, `TradeHistoryResponse` 등 전용 DTO 반환 → kista-ui 타입과 일치
- `StatisticsController`: KIS live API 직접 호출 → `PresentBalanceResult`, `PeriodProfitResult` 등 도메인 모델 그대로 반환
  - kista-ui에서 소비 시 normalizer 함수 필요 (예: `normalizePortfolio()`, `ProfitSummary` optional 필드 fallback)
  - 신규 KIS live 엔드포인트 추가 시 kista-ui 타입과 응답 필드명 반드시 대조 확인

adapter/out/
  kis/           ← KIS API Adapter (KisHttpClient 공통 헤더 처리)
  persistence/   ← JPA 인프라 (BaseAuditEntity, JpaAuditingConfig) + 어그리게이트별 서브패키지
    user/        ← UserEntity + UserJpaRepository + UserPersistenceAdapter
    account/     ← AccountEntity + AccountJpaRepository + AccountPersistenceAdapter
    tradingcycle/ ← TradingCycleEntity + TradingCycleJpaRepository + TradingCyclePersistenceAdapter
                     TradingCycleHistoryEntity + TradingCycleHistoryJpaRepository + TradingCycleHistoryPersistenceAdapter
    kistoken/    ← KisTokenEntity + KisTokenJpaRepository + KisTokenPersistenceAdapter
    audit/       ← AuditLogEntity + AuditLogJpaRepository + AuditLogPersistenceAdapter
    trade/       ← TradeHistory/PortfolioSnapshot/Order (Entity + Repo + Adapter)
    privacy/     ← PrivacyTradeMasterEntity + PrivacyTradeDetailEntity + PrivacyTradePersistenceAdapter (PrivacyTradePort 구현)
  notify/        ← TelegramAdapter (NotifyPort 구현)
```

### 레이어 의존 방향

```
adapter.in  →  domain.port.in (UseCase)
application →  domain (model + port)
adapter.out →  domain.port.out (Port 구현)
domain      →  외부 의존 없음
```

---

## 동시 수정 필요 파일 쌍

| 파일 A | 파일 B |
|--------|--------|
| 환경변수 추가/제거 | `application.yml` + `.env.example` + `docker-compose.yml` 동시 반영 |
| 새 Flyway 마이그레이션 | 해당 Entity + JpaRepository |
| JPA Entity 컬럼 변경 (`nullable`, `length`, 타입 등) | Flyway 마이그레이션과 반드시 크로스체크 — Entity의 `nullable = false` 여부와 DB 제약이 불일치하면 런타임까지 오류 미발생, 실제 null 삽입 시 `DataIntegrityViolationException` 발생 (v34 avg_price 사례) |
| `trading_cycle` 테이블 변경 | `TradingCycleEntity`(persistence/tradingcycle/) + `TradingCycleJpaRepository` + `TradingCyclePersistenceAdapter` |
| `TradingCycle` record 필드 추가/제거 | `TradingCycleEntity` + `TradingCyclePersistenceAdapter`(toEntity/toDomain) + `TradingCycleService` + `TradingCycleRequest`/`TradingCycleResponse` + 테스트에서 `new TradingCycle(...)` 직접 생성 호출처 (`TradingServiceTest`, `TradingSchedulerTest`, `TelegramAdapterTest`, `NextOrdersServiceTest`) |
| `TradingService` 필드 추가 | `TradingCycleHistoryRepository` mock 추가 필수 (`TradingServiceTest`) |
| `ExecuteTradingUseCase` 메서드 추가/변경 | `TradingService`(구현) + `TradingScheduler`(호출 방식) + `TradingServiceTest` + `TradingSchedulerTest`(verify 대상 변경) |
| Port 인터페이스 수정 | 구현 Adapter + 테스트 Mock |
| `KisOrderPort` 시그니처 변경 | `TradingService` + `FidaOrderService` + 관련 테스트 |
| `AccountService` UseCase 추가 | `AccountController` 필드 + 엔드포인트 동시 추가 |
| 컨트롤러에 새 UseCase 필드 추가 | 해당 `@WebMvcTest` 테스트에 `@MockBean` 추가 필수 (누락 시 `ApplicationContext` 실패) |
| 매매 공식 변경 | `InfiniteStrategyTypeTest` |
| `InfinitePosition` 공개 메서드 변경 | `InfinitePositionTest` + `InfiniteStrategyTypeTest` |
| `TradingSnapshot` 필드 변경 | `TelegramAdapterTest` (`new TradingSnapshot(...)` 3곳) + `TradingReport` |
| 새 KIS Adapter 추가 | 같은 패키지에 `*AdapterTest` 단위 테스트 |
| `UserPersistenceAdapter` telegramBotToken 변경 | `UserEntity` + `AesCryptoService` 암호화/복호화 패턴 확인 |
| `KisProperties` 필드 추가/제거 | `KisTokenAdapterTest` + `KisExecutionAdapterTest` + `KisProfitAdapterTest` (생성자 하드코딩) |
| `User` record 필드 추가 | `UserEntity` + `UserPersistenceAdapter` + `UserServiceTest` + `TelegramAdapterTest` + `TradingSchedulerTest` + `AccountServiceTest` + `TradingServiceTest` (`new User(...)` 호출 전파) |
| `Order` record 필드 추가 | `OrderEntity` + `OrderJpaRepository` + `OrderPersistenceAdapter` + `OrderPort` + `TradingService` |
| `TradeHistory` record 필드 추가/제거 | `TradeHistoryEntity` + `TradeHistoryPersistenceAdapter`(toEntity/toDomain) + `TradeHistoryResponse.from()` + `TradingService.toHistory()` + `TradeHistoryPersistenceAdapterTest`(history() 헬퍼) + `TradeHistoryServiceTest` + `DashboardControllerTest`(`new TradeHistory(...)` 직접 생성 포함) |
| `UpdateAccountUseCase.Command` 필드 추가 | `AccountService.update()`에 적용 로직 + `AccountRequest.toUpdateCommand()` 동시 수정 |
| `Account` record 필드 추가/제거 | `AccountEntity` + `AccountPersistenceAdapter`(toEntity/buildDomain) + `AccountService`(register/update/withStrategyStatus 3곳) + `AccountRequest`(toRegisterCommand/toUpdateCommand) + `AccountResponse.from()` + `RegisterAccountUseCase.Command` + `UpdateAccountUseCase.Command` + 테스트 9개(`AccountServiceTest` — `new Account` 및 `new UpdateAccountUseCase.Command` 직접 호출 포함, `AccountPersistenceAdapterTest`, `TradingServiceTest`, `TradingSchedulerTest`, `TelegramAdapterTest`, `KisPortfolioAdapterTest`, `KisProfitAdapterTest`, `KisOrderAdapterTest`, `KisExecutionAdapterTest`) |
| `NotifyPort` 시그니처 변경 | `TelegramAdapter` + `TradingService` + `TelegramAdapterTest` + `TradingServiceTest` |
| `KisAccountPort.getBalance` 시그니처 변경 | `KisAccountAdapter` + `TradingService` + `NextOrdersService` + 관련 테스트의 mock stub |
| `InfinitePosition` 생성자/공개 메서드 변경 | `InfinitePositionTest` + `InfiniteStrategyTypeTest` + `TradingServiceTest` + `TradingSchedulerTest` (`new InfinitePosition(...)` 직접 생성 포함) |
| `StatisticsController` 응답 타입 변경 | `StatisticsControllerTest`의 JSONPath 업데이트 필수 (예: `$.totalAssetUsd` → `$.summary.totalAssetUsd`) |
| `User.role` 변경 또는 `UserRole` 추가 | `UserEntity` + `UserPersistenceAdapter` + 모든 `new User(...)` 호출처 + `JwtIssuerService` claim |
| `AdminBootstrapProperties.kakaoIds` 변경 | `application.yml` + `.env.example` + `docker-compose.yml` + Render env |
| 새 admin 엔드포인트 추가 (`AdminXxxController`) | `AdminXxxService` + `AdminXxxUseCase`(domain/port/in) + `AdminXxxControllerTest`(`@MockBean` 필수: JwtDecoder + 사용하는 모든 UseCase) |
| `OrderController`에 엔드포인트 추가 | `GetNextOrdersUseCase` + `PlaceReservationOrderUseCase` **둘 다** `@MockBean` 필수 (`OrderControllerTest`) |
| KIS 응답 도메인 모델(`Execution`/`PresentBalanceResult.Item`/`PeriodProfitResult.Item`/`DailyTransaction`/`ReservationOrder`) 필드 변경 | 해당 KIS 어댑터(`flatMap+tryParse` 매핑) + 어댑터 단위 테스트 fixture + `kista-ui/types/trade.ts` |
| `User` 도메인 레코드에 필드 추가 | `UserEntity` + `UserPersistenceAdapter` + 모든 `new User(...)` 호출처 + **`UserResponse.from()` 반환값** + `kista-ui/types/user.ts` — `UserResponse` 누락 시 프론트엔드 API 응답에서 해당 필드 미포함 |
| `privacy_trades_master` 스키마 변경 | `PrivacyTradeMasterEntity` + `PrivacyTradeDetailEntity`(cascade 영향) + V번호 마이그레이션 |
| `privacy_trades_detail` 스키마 변경 | `PrivacyTradeDetailEntity` + V번호 마이그레이션 |
| 수량 관련 Domain record 필드 추가 | 해당 JPA Entity + Flyway 마이그레이션 + KIS 어댑터 매핑부 + DTO `from()` + kista-ui `types/` 동시 수정 |

### 인증 userId 추출 패턴
- 모든 컨트롤러: `@AuthenticationPrincipal UUID userId` 메서드 파라미터로 직접 주입 — `SecurityContextHolder` 수동 호출 금지
- `JwtAuthFilter`: principal을 `UUID` 타입으로 저장 (`String` 아님)

### 소유권 검증 패턴
- `account.verifyOwnedBy(requesterId)` — 불일치 시 `SecurityException` (컨트롤러에서 403 매핑)
- `tradingCycle.verifyOwnedBy(account)` — `cycle.accountId().equals(account.id())` 검증, 마찬가지로 `SecurityException`
- 사이클 소유권 확인 순서: `cycleRepository.findByIdOrThrow(id)` → `accountRepository.findByIdOrThrow(cycle.accountId())` → `account.verifyOwnedBy(requesterId)`
- `accountRepository.findByIdOrThrow(id)` / `cycleRepository.findByIdOrThrow(id)` — 없으면 `NoSuchElementException` (컨트롤러에서 404 매핑)
- Mockito 테스트 주의: `findByIdOrThrow`는 interface default 메서드 → Mockito가 override하므로 `when(repo.findById(...))` stub 무시됨, `when(repo.findByIdOrThrow(...))` 직접 stub 필요

### JPA Auditing
- `BaseAuditEntity` (`@MappedSuperclass`): `UserEntity`, `AccountEntity`가 상속 — `@CreatedDate`/`@LastModifiedDate`로 `createdAt`/`updatedAt` 자동 관리
- 새 엔티티에 타임스탬프 필요 시 `BaseAuditEntity` 상속; `KisTokenEntity` 등 DB DEFAULT(`insertable=false, updatable=false`) 방식 엔티티는 그대로 유지
- 서비스에서 domain record 생성 시: `updatedAt=null` (adapter가 무시, `@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null` (`@CreatedDate`가 처리)
- `toEntity()` 내에서 `setCreatedAt()`/`setUpdatedAt()` 명시적 호출 금지 — `@CreatedDate(updatable=false)` / `@LastModifiedDate`가 INSERT·UPDATE 시 자동 처리. 호출 자체가 dead code이며 `@Setter(PACKAGE)` 범위 제약과도 충돌함

### 텔레그램 알림 (notifyTradingReport)
- 계좌별 텔레그램 설정 제거됨 — `User.telegramBotToken/chatId` 사용자봇만 사용 → 미설정 시 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용

### TDA 전략 패턴 (InfiniteStrategy)
- `InfinitePosition(AccountBalance, TradingCycle.Ticker, BigDecimal price, BigDecimal multiple)` — `unitAmount K = B ÷ 20 × multiple`
- `TradingStrategy`: 단일 메서드 `buildOrders(InfinitePosition, LocalDate)` — `TradingService`가 `InfinitePosition` 1회 생성 후 전달 (SSOT)
- `TradingReport.snapshot: TradingSnapshot` — 알림용 4개 필드 (quantity, averagePrice, priceOffsetRate, targetPrice)
- `TelegramAdapter` 접근 경로: `r.snapshot().X()` (포맷 문자열 변경 금지)
- `ExecuteTradingUseCase.execute(TradingCycle cycle, Account account, User user)` — 사이클 단위 루프
- `TradingScheduler`: `cycleRepository.findAllActive()` → 사이클 단위 loop (격리 try-catch)
- `TradingCycleHistory` 저장 시점: ① `TradingCycleService.register()` — 사이클 등록 시 초기 1건 (holdings=0, avgPrice=null, usdDeposit=initialUsdDeposit) ② `TradingService.execute()` 종료 시 — 매매 실행 완료 후 1건 append
- `TradingService.execute()` 잔고 조회: KIS API 아님 → `findRecentByCycleId(cycleId, 1)` 최신 이력에서 `AccountBalance` 구성 (이력 없으면 `IllegalStateException`)
- `TradingCycle.Type` label은 **한국어 문자열** (`INFINITE="무한매수"`, `PRIVACY="기준매매표"`) — `MetaControllerTest`가 `/api/meta` 응답의 label 값을 직접 검증하므로 영문으로 변경 시 테스트 실패
- `TradingService.execute()` 전략 분기: `switch(cycle.type())` 두 블록 구조 — ① steps 3-4(현재가+PLANNED 생성) ② steps 7-9(PostClose 대기+체결+보정). 공통: 1,2,5,6,10. PRIVACY는 두 블록 모두 TODO
- PRIVACY execute() null guard 패턴: `resolvedPrice=null`, `snapshot=null` → `saveAndNotify`에서 `price != null`(portfolioSnapshot 생략), `snapshot != null`(텔레그램 리포트 생략) 조건 가드 유지 필수
- `executeBatch`: INFINITE 사이클만 현재가 일괄조회 + 단건 fallback 대상 — PRIVACY 사이클은 `price=null`로 `execute()` 전달

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trades_master` (`adapter/out/persistence/privacy/`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음** (계좌별 아닌 시스템 공통 기준)
  - `(trade_date, ticker)` UNIQUE 제약 (V24) — 하루에 종목당 마스터 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trades_detail`: 마스터 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
- FIDA 수신 흐름 (`PrivacyTradeSaveResult(id, created)` 반환):
  - `(tradeDate, ticker)` 없음 → INSERT → 201
  - 있고 내용 동일 → 200 (멱등)
  - 있고 내용 다름 → `PrivacyTradeConflictException` (`domain/model/privacy/`) → 409
- `PrivacyTradeSaveResult` (`domain/port/out/`): `UUID id` + `boolean created` — 컨트롤러가 201/200 분기
- 스케줄러 흐름: `StrategyType.PRIVACY` → `privacy_trades_master/detail` 조회 → `orders` 복사 (미구현, INFINITE와 동일 출구)
- 테이블명 컨벤션: 마스터-디테일 분리 시 `xxx_master` / `xxx_detail` 접미사 패턴 사용
