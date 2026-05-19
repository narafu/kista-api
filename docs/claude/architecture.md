## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).

```
domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체 (record 사용)
  strategy/      ← TradingStrategy 인터페이스 및 구현 (InfiniteStrategy) — @Component 허용 예외 (ArchUnit)
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스

application/
  service/       ← UseCase 구현체 (@Service), Port를 통해서만 외부 호출

adapter/in/
  schedule/      ← TradingScheduler (월~금 04:00 KST, 멀티계좌)
  web/           ← REST Controller + DTO
                    - OrderController: 예약주문(POST /reservation-orders) + 다음 주문 미리보기(GET /orders/next)
  telegram/      ← TelegramWebhookController + TelegramBotService

### DashboardController vs StatisticsController 응답 형식 차이
- `DashboardController`: DB 기반 → `PortfolioSnapshotResponse`, `TradeHistoryResponse` 등 전용 DTO 반환 → kista-ui 타입과 일치
- `StatisticsController`: KIS live API 직접 호출 → `PresentBalanceResult`, `PeriodProfitResult` 등 도메인 모델 그대로 반환
  - kista-ui에서 소비 시 normalizer 함수 필요 (예: `normalizePortfolio()`, `ProfitSummary` optional 필드 fallback)
  - 신규 KIS live 엔드포인트 추가 시 kista-ui 타입과 응답 필드명 반드시 대조 확인

adapter/out/
  kis/           ← KIS API Adapter (KisHttpClient 공통 헤더 처리)
  persistence/   ← JPA Entity + JpaRepository + PersistenceAdapter
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
| strategies 테이블 변경 | `StrategyEntity` + `StrategyJpaRepository` + `AccountPersistenceAdapter` + `AccountPersistenceAdapterTest`(`strategyEntity()` 헬퍼에 새 필드 세팅 필수 — 누락 시 `buildDomain()` NPE) |
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
| `PlannedOrder` 변경 | `PlannedOrderPort` + `PlannedOrderPersistenceAdapter` + `PlannedOrderEntity` + `PlannedOrderJpaRepository` + `TradingService` (savePlannedOrders/executePlannedOrders 메서드) |
| `UpdateAccountUseCase.Command` 필드 추가 | `AccountService.update()`에 적용 로직 + `AccountRequest.toUpdateCommand()` 동시 수정 |
| `Account` record 필드 추가/제거 | `AccountEntity` + `AccountPersistenceAdapter`(toEntity/buildDomain) + `AccountService`(register/update/withStrategyStatus 3곳) + `AccountRequest`(toRegisterCommand/toUpdateCommand) + `AccountResponse.from()` + `RegisterAccountUseCase.Command` + `UpdateAccountUseCase.Command` + 테스트 9개(`AccountServiceTest` — `new Account` 및 `new UpdateAccountUseCase.Command` 직접 호출 포함, `AccountPersistenceAdapterTest`, `TradingServiceTest`, `TradingSchedulerTest`, `TelegramAdapterTest`, `KisPortfolioAdapterTest`, `KisProfitAdapterTest`, `KisOrderAdapterTest`, `KisExecutionAdapterTest`) |
| `NotifyPort` 시그니처 변경 | `TelegramAdapter` + `TradingService` + `TelegramAdapterTest` + `TradingServiceTest` |
| `StatisticsController` 응답 타입 변경 | `StatisticsControllerTest`의 JSONPath 업데이트 필수 (예: `$.totalAssetUsd` → `$.summary.totalAssetUsd`) |
| `User.role` 변경 또는 `UserRole` 추가 | `UserEntity` + `UserPersistenceAdapter` + 모든 `new User(...)` 호출처 + `JwtIssuerService` claim |
| `AdminBootstrapProperties.kakaoIds` 변경 | `application.yml` + `.env.example` + `docker-compose.yml` + Render env |
| 새 admin 엔드포인트 추가 (`AdminXxxController`) | `AdminXxxService` + `AdminXxxUseCase`(domain/port/in) + `AdminXxxControllerTest`(`@MockBean` 필수: JwtDecoder + 사용하는 모든 UseCase) |
| `OrderController`에 엔드포인트 추가 | `GetNextOrdersUseCase` + `PlaceReservationOrderUseCase` **둘 다** `@MockBean` 필수 (`OrderControllerTest`) |

### 인증 userId 추출 패턴
- 모든 컨트롤러: `@AuthenticationPrincipal UUID userId` 메서드 파라미터로 직접 주입 — `SecurityContextHolder` 수동 호출 금지
- `JwtAuthFilter`: principal을 `UUID` 타입으로 저장 (`String` 아님)

### 소유권 검증 패턴 (Account 도메인)
- `account.verifyOwnedBy(requesterId)` — 불일치 시 `SecurityException` (컨트롤러에서 403 매핑)
- `accountRepository.findByIdOrThrow(id)` — 없으면 `NoSuchElementException` (컨트롤러에서 404 매핑)
- Mockito 테스트 주의: `findByIdOrThrow`는 interface default 메서드 → Mockito가 override하므로 `when(repo.findById(...))` stub 무시됨, `when(repo.findByIdOrThrow(...))` 직접 stub 필요

### JPA Auditing
- `BaseAuditEntity` (`@MappedSuperclass`): `UserEntity`, `AccountEntity`가 상속 — `@CreatedDate`/`@LastModifiedDate`로 `createdAt`/`updatedAt` 자동 관리
- 새 엔티티에 타임스탬프 필요 시 `BaseAuditEntity` 상속; `KisTokenEntity` 등 DB DEFAULT(`insertable=false, updatable=false`) 방식 엔티티는 그대로 유지
- 서비스에서 domain record 생성 시: `updatedAt=null` (adapter가 무시, `@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null` (`@CreatedDate`가 처리)

### 텔레그램 알림 (notifyTradingReport)
- 계좌별 텔레그램 설정 제거됨 — `User.telegramBotToken/chatId` 사용자봇만 사용 → 미설정 시 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용

### TDA 전략 패턴 (InfiniteStrategy)
- `InfinitePosition` (`domain/model`): 잔고·종목·현재가로부터 모든 매매 변수 계산 + TDA 행위 메서드 (`isEarlyStage()`, `isDepositDeficient()`, `calcXxx()`) + `toSnapshot()`
- `TradingStrategy`: 단일 메서드 `buildOrders(InfinitePosition, LocalDate)` — `TradingService`가 `InfinitePosition` 1회 생성 후 전달 (SSOT)
- `TradingReport.snapshot: TradingSnapshot` — 알림용 4개 필드 (quantity, averagePrice, priceOffsetRate, targetPrice)
- `TelegramAdapter` 접근 경로: `r.snapshot().X()` (포맷 문자열 변경 금지)
