# 3차 사이클 — 클린코드 리팩토링 계획 (kista-api + kista-ui)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 1·2차 사이클에서 다루지 않은 죽은 코드·중복·가시성·테스트 헬퍼 미채택 문제를 정리해 유지보수 비용을 낮춘다.

**Architecture:** 변경 없음 — 기존 Hexagonal(api) / FSD(ui) 구조 그대로, 파일 내부 정리와 헬퍼 추출만 수행.

**Tech Stack:** Java 21 + Spring Boot 3 (kista-api), Next.js 16 App Router + TypeScript (kista-ui)

## Context

2026-07-11 1차(4렌즈 검토 10 Task) + 2차(클린코드 9 Task + Minor 2건) 사이클 완료 후, 새 클린코드 사이클을 실행한다. Explore 에이전트 3개(api 메인 / api 테스트·설정 / ui)로 양 레포를 재스캔하고, 죽은 코드 주장 9건은 오케스트레이터가 grep으로 직접 검증 완료. 1·2차에서 완료·기각된 항목은 전부 제외했다.

**실행 방식**: superpowers:subagent-driven-development — Task별 fresh implementer + task reviewer, 완료 후 whole-branch 최종 리뷰. main 직접 커밋(2차 사이클과 동일 전례).

**병렬화 전략**: kista-api와 kista-ui는 완전히 독립된 git 저장소이므로 **두 트랙을 동시에 진행**한다 (api 트랙: Task 1→2→5→6→7, ui 트랙: Task 3→4→8). 단, **동일 저장소 내에서는 fresh implementer를 동시에 두 개 이상 디스패치하지 않는다** — 같은 브랜치에 대한 동시 커밋 충돌을 피하기 위한 스킬의 고정 규칙. 즉 병렬성은 "레포 간"으로 한정, "레포 내"는 순차.

## 사용자 확정 사항 (열린 질문 해소됨)

1. **WebMvcTestSupport**: 채택 — 컨트롤러 테스트 ~20개의 로컬 인증 헬퍼를 일괄 통일 (삭제 아님)
2. **apiMsg 에러 토스트 통일**: 포함 — 서버 메시지 우선 + 기존 고정 문구 fallback (사용자 가시 문구 변경 승인됨 — 이 Task만 "동작 불변" 원칙의 승인된 예외)

## Global Constraints

- **동작 불변** — 기존 테스트 전체 통과로 검증 (Task 4 토스트 문구만 승인된 예외)
- 매매 공식·주문 생성 로직(`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`) 내부 로직 변경 절대 금지 — 접근제어자·주변 정리만 허용
- Java 파일 BOM(`\xef\xbb\xbf`) 삽입 금지 — 커밋 전 확인
- 커밋: 한글 메시지 + Conventional Commit 접두사, author `narafu <narafu@kakao.com>`, **push 금지**
- kista-api 검증 게이트: `./gradlew compileJava` + `./gradlew test` (실패 진단: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`)
- kista-ui 검증 게이트: `npm run typecheck` + `npm run test`
- 테스트 규칙: `@WithMockUser` 금지, principal은 UUID, `@Execution(SAME_THREAD)` 유지

## 모델 라우팅

| 역할 | 모델 | 근거 |
|---|---|---|
| 오케스트레이션 | 현재 세션 | 컨텍스트 보유, 두 트랙 조율 |
| Task 1, 8 implementer | **haiku** | 검증된 목록의 기계적 삭제/치환 |
| Task 7 implementer | **haiku** | 접근제어자만 변경하는 기계적 작업 |
| Task 2, 3, 4, 5, 6 implementer | **sonnet** | 다파일 조율·리터럴 불일치 판단·FSD 배치 판단 |
| Task reviewer (전 Task) | **sonnet** | diff 규모 중소, 스펙 대조 중심 |
| 최종 whole-branch 리뷰 | **opus** | 양 레포 교차 검토 |

---

## Tasks (영향력 내림차순 — [api]/[ui] 트랙은 병렬, 트랙 내부는 순차)

### Task 1 [api, haiku]: 죽은 코드 제거 — 포트 메서드 9건 + 미사용 import 6건

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/OrderPort.java`, `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/domain/port/out/CyclePositionPort.java`, `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java`, `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionJpaRepository.java`
- Modify: `src/main/java/com/kista/domain/port/out/MarketHolidayStorePort.java`, `src/main/java/com/kista/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/domain/port/out/PrivacyTradePort.java`
- Modify: `src/main/java/com/kista/domain/port/out/StrategyPort.java`
- Modify: `src/main/java/com/kista/domain/port/out/StrategyVersionPort.java`, `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyVersionPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/domain/port/in/UserUseCase.java`, `src/main/java/com/kista/application/service/user/UserService.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossAuthApi.java`, `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleEntity.java`, `src/main/java/com/kista/adapter/in/web/AdminObservabilityController.java`, `src/main/java/com/kista/adapter/in/web/MarketHolidayController.java`, `src/main/java/com/kista/application/service/account/AccountService.java`, `src/main/java/com/kista/application/service/strategy/StrategyService.java`

**왜**: 호출자 없는 코드가 포트 인터페이스를 오염. 전 건 grep으로 직접 검증 완료(선언+구현만 존재, 호출부 0).

**제거 대상 (포트 선언 + 어댑터 구현 + 연쇄로 미사용이 되는 JpaRepository 쿼리 메서드까지)**:
- `OrderPort.java:62` `void updatePlannedOrder(UUID orderId, BigDecimal price, int quantity);` + `OrderPersistenceAdapter.java:128-138`의 구현
- `CyclePositionPort.java:40` `List<CyclePositionHistoryEntry> findRecentGlobal(int limit);` + `CyclePositionPersistenceAdapter.java:72`의 구현
- `CyclePositionPort.java:46` `List<CyclePositionHistoryEntry> findBetween(LocalDate from, LocalDate to);` + `CyclePositionPersistenceAdapter.java:82-85`의 구현 (내부에서 호출하는 `CyclePositionJpaRepository.findBetweenDates`도 다른 호출자 없으면 함께 제거)
- `CyclePositionPort.java:57` `void softDeleteTodayByStrategyId(UUID strategyId, LocalDate kstDate);` + `CyclePositionPersistenceAdapter.java:115`의 구현
- `MarketHolidayStorePort.java:7` `boolean existsByDate(LocalDate date);` + `MarketCalendarPersistenceAdapter.java:37-39`의 구현
- `PrivacyTradePort.java:19-21`의 default 메서드 `findCurrentBase()`
- `StrategyPort.java:41-48`의 default 메서드 `findActiveTicker(UUID accountId)`
- `StrategyVersionPort.java:23` `void closeActiveVersion(UUID strategyId);` + `StrategyVersionPersistenceAdapter.java:46`의 구현
- `UserUseCase.java:10` `User getByKakaoId(String kakaoId);` + `UserService.java:49-54`의 구현
- 미사용 import 6건: `TossAuthApi.java:22`(`java.util.Optional`), `:24`(`java.util.concurrent.ConcurrentHashMap`), `StrategyCycleEntity.java:4`(`com.kista.domain.model.strategy.StrategyCycle`), `AdminObservabilityController.java:9`(`com.kista.domain.model.admin.AuditLog`), `MarketHolidayController.java:9`(`io.swagger.v3.oas.annotations.media.Schema`), `AccountService.java:10`(`com.kista.domain.port.out.broker.BrokerConnectionTestPort`), `StrategyService.java:20`(`java.time.LocalDate`)

**주의**: 각 제거 전 반드시 `grep -rn "<메서드명>" src`로 잔존 참조 0건을 재확인할 것 (implementer 본인이 직접 재검증 — 오케스트레이터 검증을 신뢰하되 삭제 직전 1회 더 확인).

- [ ] Step 1: 위 9개 포트 메서드+구현을 하나씩 grep 재확인 후 제거
- [ ] Step 2: 미사용 import 6건 제거
- [ ] Step 3: `./gradlew compileJava` 통과 확인
- [ ] Step 4: `./gradlew test` 전체 통과 확인
- [ ] Step 5: 커밋 (`refactor(domain): 미사용 포트 메서드 9건·미사용 import 6건 제거`)

### Task 2 [api, sonnet]: WebMvcTestSupport 채택 — 컨트롤러 테스트 ~20개 인증 헬퍼 통일

**Files:**
- Reference (변경 없음, 이미 존재): `src/test/java/com/kista/support/WebMvcTestSupport.java`
- Modify: `src/test/java/com/kista/adapter/in/web/` 하위 `@WebMvcTest` 클래스 전체 (AccountControllerTest, AdminAccountControllerTest, AdminDashboardControllerTest, AdminPingControllerTest, AdminTradeControllerTest, AdminUserControllerTest, AuthControllerTest, DashboardControllerTest, FearGreedControllerTest, MarketHolidayControllerTest, MetaControllerTest, OrderCancelControllerTest, SettingsControllerTest, StatisticsControllerTest, TossStatisticsControllerTest, TradeStreamControllerTest, TradingCycleControllerTest, FcmControllerTest, AdminObservabilityControllerTest, AdminPrivacyTradeControllerTest 등)

**왜**: `WebMvcTestSupport`가 존재하나 사용처 0. `@WebMvcTest` 클래스 ~20개가 각자 `new UsernamePasswordAuthenticationToken(uuid, null, List.of(...))` 헬퍼를 로컬 재구현 — 이번 스캔 최대 중복.

**방법**: `WebMvcTestSupport`의 실제 API를 먼저 Read로 확인한 뒤, 각 테스트의 로컬 헬퍼 호출부를 `import static com.kista.support.WebMvcTestSupport.*`로 교체. 로컬 헬퍼가 `WebMvcTestSupport`와 시그니처·동작이 다른 경우(예: role 커스텀)는 `WebMvcTestSupport`에 오버로드를 추가하되 기존 메서드 시그니처는 변경 금지. 치환 불가능한 특수 케이스는 그대로 두고 보고에 명시.

- [ ] Step 1: `WebMvcTestSupport.java` 전체 Read
- [ ] Step 2: 20개 컨트롤러 테스트 각각에서 로컬 인증 헬퍼를 static import로 교체 (필요 시 오버로드 추가)
- [ ] Step 3: `./gradlew test --tests 'com.kista.adapter.in.web.*'` 통과 확인
- [ ] Step 4: `./gradlew test` 전체 통과 확인
- [ ] Step 5: `grep -rln "UsernamePasswordAuthenticationToken" src/test`로 잔존 로컬 생성 감소 확인
- [ ] Step 6: 커밋 (`refactor(test): WebMvcTestSupport 채택 — 컨트롤러 테스트 인증 헬퍼 통일`)

### Task 3 [ui, sonnet]: 위젯 중복 추출 — range 필터 reducer + 필터 UI 컴포넌트

**Files:**
- Modify: `widgets/account-detail/TradesTab.tsx`, `widgets/cycle-history/StrategyTradesTab.tsx`
- Modify: `widgets/cycle-history/CycleHistoryTable.tsx`, `widgets/strategy-detail/StrategyOrderHistory.tsx`
- Create: `shared/lib/hooks/use-range-filter-state.ts` (또는 기존 `shared/lib/` 하위 적절한 위치), `shared/ui/range-filter/` (버튼+날짜입력 프레젠테이셔널 컴포넌트)

**왜**: 두 쌍의 완전 중복.
- `widgets/account-detail/TradesTab.tsx:8-24` ↔ `widgets/cycle-history/StrategyTradesTab.tsx:10-26` — State/Action/reducer/INITIAL이 토씨까지 동일 (차이는 사용하는 쿼리 훅과 prop명 `accountId` vs `strategyId`뿐)
- `widgets/cycle-history/CycleHistoryTable.tsx:61-96` ↔ `widgets/strategy-detail/StrategyOrderHistory.tsx:64-99` — range 버튼(7d/30d/all/custom) + `PageSizeSelector` + 커스텀 날짜 `<input type="date">` 2개 마크업/클래스가 완전 동일 (onClick/onChange 호출 방식만 다름)

**방법**: reducer(State/Action/reducer/INITIAL)를 `shared/lib/`의 공용 훅으로 추출해 두 위젯에서 재사용. range 버튼+날짜입력 UI 블록을 `shared/ui/`의 프레젠테이셔널 컴포넌트로 추출하고 콜백을 props로 주입받게 한다. FSD 규칙 준수(shared는 entities/features/widgets import 금지). 마크업/클래스명은 그대로 이동 — 시각 변화 0.

- [ ] Step 1: 4개 파일 전체 Read로 중복 블록 정확한 범위 확인
- [ ] Step 2: 공용 훅 파일 작성 (state/action/reducer/initial 이동)
- [ ] Step 3: 공용 UI 컴포넌트 파일 작성 (range 버튼+날짜 input, 콜백 props)
- [ ] Step 4: 4개 위젯에서 로컬 정의 제거하고 신규 훅/컴포넌트 import로 교체
- [ ] Step 5: `npm run typecheck` 통과 확인
- [ ] Step 6: `npm run test` 통과 확인
- [ ] Step 7: 커밋 (`refactor(widgets): range 필터 reducer·UI 공용 추출 — 중복 4파일 통합`)

### Task 4 [ui, sonnet]: apiMsg 승격 + 에러 토스트 서버 메시지 통일 (승인된 동작 변경)

**Files:**
- Modify: `entities/strategy/hooks/useStrategyQueries.ts` (apiMsg 정의부 제거, import로 교체)
- Create 또는 Modify: `shared/lib/api-client.ts` (또는 인접 shared/lib 모듈에 apiMsg 이동)
- Modify: `entities/account/hooks/useAccountMarginQuery.ts`, `entities/admin/hooks/useAdminQueries.ts`, `entities/user/hooks/useUserQueries.ts`

**왜**: `entities/strategy/hooks/useStrategyQueries.ts:20-27`의 `apiMsg`(서버 `detail`/`message` 추출)만 서버 메시지를 표시하고, `entities/account/hooks/useAccountMarginQuery.ts:47,62` · `entities/admin/hooks/useAdminQueries.ts:29,42,56,69` · `entities/user/hooks/useUserQueries.ts`는 고정 문자열만 표시 — 패턴 혼용. **사용자 승인**: 이 Task는 토스트 문구가 "고정 문구 → 서버 메시지 우선"으로 바뀌는 것을 허용된 예외로 승인받음.

**방법**: `apiMsg` 함수 전체를 `shared/lib/api-client.ts`(없으면 신규 생성, 있으면 그 파일에 추가)로 이동. `useStrategyQueries.ts`는 로컬 정의를 지우고 import로 교체(순수 이동, 동작 무변화). 나머지 3개 파일의 각 `onError: () => toast.error('고정 문구')`를 `onError: (e) => toast.error(apiMsg(e, '고정 문구'))` 형태로 변경 — 기존 고정 문구를 fallback 인자로 그대로 보존.

- [ ] Step 1: `useStrategyQueries.ts:20-27`의 `apiMsg` 구현 전체 Read
- [ ] Step 2: `shared/lib/api-client.ts`로 이동, `useStrategyQueries.ts`는 import로 교체
- [ ] Step 3: `useAccountMarginQuery.ts`/`useAdminQueries.ts`/`useUserQueries.ts`의 각 `onError` 블록을 `apiMsg(e, 기존문구)` 형태로 변경 (기존 문구 문자열 그대로 보존해 fallback으로 사용)
- [ ] Step 4: `npm run typecheck` 통과 확인
- [ ] Step 5: `npm run test` 통과 확인
- [ ] Step 6: 커밋 (`refactor(entities): apiMsg 공용화 — 에러 토스트 서버 메시지 표시로 통일`)

### Task 5 [api, sonnet]: TradingService·AdminObservabilityController 중복 로직 추출

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AdminObservabilityController.java`

**왜/방법**:
- `TradingService.java:83-93`(executeBatch) ↔ `:264-272`(placeOpenOrders) — cycleTickers/priceAccount/startPriceSnapshots/privacyBase 조회 블록 중복. private record 반환 헬퍼(예: `loadPriceContext(contexts, date)`)로 추출. **매매 계산 로직 자체는 건드리지 않음 — 조회 블록 이동만.**
- `AdminObservabilityController.java:42-43` ↔ `:59-60` — `LocalDate`→`Instant` 변환 중복 (기본값이 `null` vs `EPOCH`/`now()`로 다름). fallback 파라미터화한 `private static Instant toInstantOrDefault(...)` 헬퍼로 추출.

- [ ] Step 1: `TradingService.java:83-93`, `:264-272` 정확한 범위 Read
- [ ] Step 2: private record 헬퍼로 추출, 두 호출부 교체
- [ ] Step 3: `AdminObservabilityController.java:42-43`, `:59-60` Read 후 private static 헬퍼로 추출
- [ ] Step 4: `./gradlew compileJava` 통과 확인
- [ ] Step 5: `./gradlew test --tests 'com.kista.application.service.trading.*'` 통과 확인
- [ ] Step 6: `./gradlew test` 전체 통과 확인
- [ ] Step 7: 커밋 (`refactor(trading): 가격·기준표 조회 블록 중복 제거 + admin 날짜변환 헬퍼 추출`)

### Task 6 [api, sonnet]: DomainFixtures 이관 확대 — 13개 테스트 파일

**Files:**
- Modify: `src/test/java/com/kista/adapter/in/schedule/BatchContextFactoryTest.java`
- Modify: `src/test/java/com/kista/application/service/admin/AdminReorderServiceTest.java`, `AdminTradeCorrectionServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/auth/TokenServiceTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AuthControllerTokenTest.java`, `DevAuthControllerTest.java`
- Modify: `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/CyclePositionPersistorTest.java`, `CycleRotationServiceTest.java`, `TradingReporterTest.java`, `TradingServiceTest.java`, `VrCycleRolloverServiceTest.java`, `ManualTradingServiceTest.java`, `OrderCancelServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/user/UserProfileServiceTest.java`

**왜**: 2차에서 신설한 `DomainFixtures`(`activeUser(id, channel)`/`activeUserWithTelegram(id)`/`telegramUser(id, botToken, chatId)`/`kisAccount(id, userId)`)를 쓸 수 있는데 로컬 중복 생성 중인 파일 13개.

**대상과 치환 방법**:
- `BatchContextFactoryTest.java:57-64` — `mockAccount()`/`mockUser()` → `DomainFixtures.kisAccount(accountId, USER_ID)` / `DomainFixtures.activeUserWithTelegram(USER_ID)`
- `AdminReorderServiceTest.java:224-227` — `user()` → `activeUserWithTelegram` (kakaoId 리터럴 `"kakao"` vs `"kakao-1"` 차이는 단언에 안 쓰이는지 먼저 확인 후 교체)
- `AdminTradeCorrectionServiceTest.java:63-64,102-103` — 인라인 반복 2회 → `activeUserWithTelegram(USER_ID)`
- `TokenServiceTest.java:199-203` — `mockUser()`(NotificationChannel.FCM) → `DomainFixtures.activeUser(id, NotificationChannel.FCM)`
- `AuthControllerTokenTest.java:163-167` — `stubUser()`(FCM) → `DomainFixtures.activeUser(id, NotificationChannel.FCM)`
- `StrategyServiceTest.java:116-125` — `ownerAccount()`/`activeUser()` → `kisAccount()`/`activeUserWithTelegram()`
- `CyclePositionPersistorTest.java:52-57`, `CycleRotationServiceTest.java:57-64`, `TradingReporterTest.java:50-66`, `TradingServiceTest.java:99-102`, `VrCycleRolloverServiceTest.java:54-61` — 동일 static ACCOUNT/USER 필드 패턴 → `DomainFixtures.kisAccount`/`activeUserWithTelegram`
- `ManualTradingServiceTest.java:62-78` — ACCOUNT는 `kisAccount`, USER(NotificationChannel.NONE)는 `DomainFixtures.activeUser(id, NONE)`
- `OrderCancelServiceTest.java:61-62` — `kisAccount()`로 교체
- `UserProfileServiceTest.java:32-35` — `user(id)` → `DomainFixtures.activeUser(id, NONE)`
- `DevAuthControllerTest.java:83-86`, `:97-100` — `devToken_returns_token`/`devAdminToken_returns_token` 두 테스트에 완전 동일한 4줄 stub(`issueRefreshToken`/`cookieHelper.issue`/`jwtIssuerService.expiresInSeconds` 3줄 공통 + `jwtIssuerService.issue` 반환값만 테스트별 다름) → `@BeforeEach`로 공통 3줄 추출

**이번 범위 제외 (판단 필요, 손대지 말 것)**: `AdminServiceTest.java:46-49`(`user(id, status)` — status 파라미터화, DomainFixtures 시그니처에 없음), `UserServiceTest.java:49-70,333,360`(status/lastReappliedAt 다변화 + 의미 있는 커스텀 닉네임), `DevAuthControllerTest.java:56-68`(`MOCK_USER`/`MOCK_ADMIN_USER` 고정 UUID+role — DomainFixtures가 role 파라미터 미지원)

**주의(판단 포인트)**: 각 파일 교체 전, 그 테스트가 kakaoId/닉네임 등 리터럴 값 자체를 `assertThat(...)`으로 검증하는지 먼저 확인 — 단언에 쓰이면 해당 파일은 치환하지 말고 보고에 남길 것.

- [ ] Step 1: 13개 파일을 순서대로 Read하며 각 리터럴이 단언에 쓰이는지 확인
- [ ] Step 2: 단언에 안 쓰이는 파일들을 DomainFixtures 호출로 교체
- [ ] Step 3: `DevAuthControllerTest.java`에 `@BeforeEach` 공통 stub 추출
- [ ] Step 4: `./gradlew test` 전체 통과 확인
- [ ] Step 5: 커밋 (`refactor(test): DomainFixtures 이관 확대 — 13개 테스트 파일 중복 픽스처 제거`)

### Task 7 [api, haiku]: 어댑터·전략 구현체 public → package-private — 16개 클래스

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/kis/KisAuthApi.java`, `KisOrderApi.java`, `KisPriceApi.java`, `KisTradingApi.java`, `KisHttpClient.java`, `KisExchangeRegistry.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossAuthApi.java`, `TossCandleApi.java`, `TossHoldingsApi.java`, `TossMarketApi.java`, `TossOrderApi.java`, `TossPriceApi.java`, `TossHttpClient.java`
- Modify: `src/main/java/com/kista/domain/strategy/InfiniteCycleOrderStrategy.java`, `PrivacyCycleOrderStrategy.java`, `VrCycleOrderStrategy.java`

**왜**: 같은 패키지에서만 참조되는데 `public class`로 선언되어 있음. Spring 빈 수집(`List<XxxPort>`)은 클래스 가시성과 무관하게 동작하고, 대응 테스트도 전부 동일 패키지에 위치 — 가시성 좁히기가 안전.

**주의**: `domain/strategy` 3개 클래스는 매매 로직 보호 대상이지만, 여기서 바꾸는 것은 **클래스 선언부의 `public` 키워드 제거뿐** — 메서드 본문·필드·로직은 한 글자도 건드리지 않는다.

- [ ] Step 1: 16개 파일에서 `public class X` → `class X` (선언부만)
- [ ] Step 2: `./gradlew compileJava` 통과 확인 (다른 패키지에서 참조 시 컴파일 에러로 즉시 드러남)
- [ ] Step 3: `./gradlew test` 전체 통과 확인
- [ ] Step 4: `./gradlew test --tests 'com.kista.architecture.*'` ArchUnit 통과 확인
- [ ] Step 5: 커밋 (`refactor(broker): KIS·Toss 어댑터 및 CycleOrderStrategy 구현체 package-private화 — 16개 클래스`)

### Task 8 [ui, haiku]: 소소한 기계 정리 묶음

**Files:**
- Modify: `features/strategy/create-strategy/StrategyFormSkeleton.tsx`
- Modify: `widgets/market-holiday-calendar/WeeklyMarketCalendar.tsx`
- Modify: `features/strategy/create-strategy/sections/ReadOnlySeedSection.tsx`, `widgets/fear-greed-card/FearGreedGauge.tsx`, `widgets/fear-greed-card/FearGreedTrend.tsx`, `widgets/admin-trade-list/AdminTradesFeedback.tsx`, `widgets/strategy-list/StrategyList.tsx`
- Modify: `eslint.config.mjs`, `package.json`

**변경 내용**:
- `StrategyFormSkeleton.tsx:3-5` 로컬 `PulseBox`(`animate-pulse rounded bg-muted`) 제거 → `components/ui/skeleton.tsx`의 `<Skeleton className="rounded ...">` 사용 (className에 `rounded` 명시해 완전 무변화 유지)
- `WeeklyMarketCalendar.tsx:71` — `` `${daySummary.netAmountUsd >= 0 ? '+' : '-'}$${fmtUsd(Math.abs(daySummary.netAmountUsd), 0)}` `` → `` `$${fmtSignedUsd(daySummary.netAmountUsd, 0)}` `` (`shared/lib/format`의 기존 `fmtSignedUsd` import 추가). 116행의 한글 라벨 혼용 케이스는 손대지 말 것.
- 불필요 `'use client'` 제거 6개(1행): `StrategyFormSkeleton.tsx`, `ReadOnlySeedSection.tsx`, `FearGreedGauge.tsx`, `FearGreedTrend.tsx`, `AdminTradesFeedback.tsx`, `StrategyList.tsx` — 각 파일이 hooks/이벤트핸들러/브라우저 API 없이 순수 props→JSX이고 소비처가 이미 `'use client'`임을 제거 전 재확인
- `eslint.config.mjs`가 직접 import하는 `@next/eslint-plugin-next`, `eslint-plugin-react-hooks`, `typescript-eslint`를 `package.json`의 `devDependencies`에 명시 추가 — 현재 `node_modules`에 설치된 버전(전이 의존성)과 동일한 semver 범위로 고정

- [ ] Step 1: `StrategyFormSkeleton.tsx`의 `PulseBox` 제거, `Skeleton` 교체
- [ ] Step 2: `WeeklyMarketCalendar.tsx:71`을 `fmtSignedUsd`로 교체
- [ ] Step 3: 6개 파일의 `'use client'` 제거 전 각 소비처가 이미 client 컴포넌트인지 재확인 후 제거
- [ ] Step 4: `node_modules`에서 3개 패키지의 실제 설치 버전 확인 (`npm ls @next/eslint-plugin-next eslint-plugin-react-hooks typescript-eslint`) 후 `package.json` devDependencies에 추가
- [ ] Step 5: `npm run typecheck` 통과 확인
- [ ] Step 6: `npm run lint` 정상 동작 확인
- [ ] Step 7: `npm run test` 통과 확인
- [ ] Step 8: 커밋 (`refactor(ui): 스켈레톤·부호포맷 공용화, 불필요한 use client 제거, eslint 의존성 명시`)

### 최종: whole-branch 리뷰 [opus]

양 레포 사이클 시작 커밋~HEAD diff를 `review-package`로 묶어 최종 리뷰. Minor 발견은 ledger에 기록된 목록과 함께 triage.

---

## 기각·보류 (재론 방지 기록)

- **KisAuthApi/TossAuthApi catch 블록 공통화** — 기각: 브로커 독립성 훼손 우려, 로그 메시지 상이, 이득 낮음
- **`qty` 지역변수명 (PrivacyStrategy 등)** — 기각: 매매 공식 보호 파일, 규칙은 필드/DTO 대상이라 위반 아님
- **`PresentBalanceResult.aggregateToss` 60줄 분리** — 기각: 단일 응집 계산, 매매 공식 인접
- **`lib/utils.ts` shadcn shim 제거** — 보류: shadcn CLI 재생성 워크플로 의존 가능성, 유지
- **DomainFixtures status/role 파라미터 오버로드 (AdminServiceTest/UserServiceTest/DevAuthControllerTest)** — 보류: 로컬 전용 헬퍼로 충분, 다음 사이클 후보
- **application-local.yml 자격증명 우려** — 오탐 확인: `.gitignore`로 미추적 (git ls-files 검증 완료)

## 실행 순서

1. 이 계획 파일을 커밋 (`git add -f docs/superpowers/plans/2026-07-12-clean-code-cycle3.md`)
2. api 트랙(Task 1→2→5→6→7)과 ui 트랙(Task 3→4→8)을 **병렬로 시작** — 각 트랙 내부는 fresh implementer 순차 디스패치
3. Task마다: implementer(지정 모델) → review-package → reviewer(sonnet) → 필요시 fix → ledger 기록
4. 양 트랙 전체 완료 후: 양 레포 최종 whole-branch 리뷰(opus) + HEAD 기준 전체 테스트/typecheck 재실행
5. push는 사용자 명시 요청 시에만
