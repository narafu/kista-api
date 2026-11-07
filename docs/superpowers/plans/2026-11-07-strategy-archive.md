# 전략 보관(ARCHIVED) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `StrategyStatus.ARCHIVED`를 추가해 보관한 전략은 매매·변경을 막고 목록에서 숨기되 통계에는 남긴다(CQ-37).

**Architecture:** trading-core `trading` 모듈 안에서 끝난다. 상태 상수 추가 + `Strategy.isArchived()`/`requireNotArchived()` 도메인 가드, `StrategyService.archive` + `PATCH /api/trading-cycles/{id}/archive`, 변경 경로 가드, 목록 호출부 인라인 필터. 통계용 조회(`findByAccountIds`)는 건드리지 않는다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + Mockito + AssertJ, `@WebMvcTest`, `DataJpaTestBase`.

**Spec:** `docs/superpowers/specs/2026-11-07-strategy-archive-design.md`

## Global Constraints

- 상수: `ARCHIVED("보관됨")` — DB `@Enumerated(STRING)`, 마이그레이션 없음(`VARCHAR(20)`, CHECK 없음).
- 보관은 한 방향. 보관 해제 API·메서드를 만들지 않는다.
- `StrategyPort.findByAccountIds`·`findByAccountId`의 동작을 바꾸지 않는다(통계가 공유).
- 예외: 상태 위반은 `IllegalStateException`(→400), application·domain 메시지는 "~니다."로 끝남(`ExceptionMessageFormatTest`).
- 보관 거부 문구(정확히): `"보관된 전략은 변경할 수 없습니다."`, `"이미 보관된 전략입니다."`, `"보유 수량을 모두 매도한 뒤 보관할 수 있습니다."`, `"오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다."`, 취소 실패: `"증권사 주문 취소에 실패한 주문이 있어 보관할 수 없습니다. 잠시 후 다시 시도해 주세요."`
- 주석은 `//` 인라인만, 신규 코드에 역할·단계 주석. 로그는 `[strategyId={}]` 접두사.
- `@Transactional` 안에서 브로커 호출 금지 — archive는 pause/delete처럼 `@Transactional(propagation = NOT_SUPPORTED)`, 상태 저장은 `StrategyStateWriter`.
- 테스트 명령은 서브프로젝트 지정: `./gradlew :trading-core:test --tests '<FQCN>' 2>&1 | grep -E "FAILED|BUILD|tests completed"`.
- 구현 서브에이전트는 git add/commit을 하지 않는다. 커밋은 전체 태스크와 리뷰가 끝난 뒤 컨트롤러가 한 번(한글 Conventional Commit, author `narafu <narafu@kakao.com>`).

## Review Focus

1. 당일 체결된 PAUSED 전략을 마감 리포트 전에 보관 → 체결이 원장에 반영되지 않음. 기대: 보관 거부(Task 2 테스트 `archive_rejects_when_today_orders_not_reported`).
2. 보관 전략을 삭제 → 삭제 경로 `stateWriter.pause`가 ARCHIVED를 PAUSED로 바꾸지 않고 주문 취소·소프트 삭제는 정상 진행(Task 1 `pause_keeps_archived_status`, Task 2 `delete_archived_strategy_soft_deletes`).
3. 관리자 상태 변경으로 ARCHIVED 요청, 또는 ARCHIVED 전략에 ACTIVE 요청 → 400, 상태 불변(Task 3).
4. 보관 후 같은 종목 신규 등록 → 409가 아니라 등록 성공(Task 4 `validateUniqueTicker_ignores_archived`).
5. 보관 전략이 통계에서 빠지는 회귀(누가 `findByAccountIds`에 필터를 넣음) → DB 테스트로 잠금(Task 4 `findByAccountIds_includes_archived`).

---

### Task 1: 상태 상수·도메인 가드·덮어쓰기 방어·`isActive()` 치환

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyStatus.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/Strategy.java`
- Modify: `trading-core/.../trading/application/service/StrategyStateWriter.java:25`
- Modify: `trading-core/.../trading/application/port/output/StrategyPort.java:50` (default `pause`)
- Modify: `trading-core/.../trading/application/service/TradingService.java:88,240`
- Modify: `trading-core/.../trading/application/service/CloseReportScope.java:16`
- Modify: `trading-core/.../trading/application/service/CyclePositionPersistor.java:73`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/StrategyTest.java` (없으면 생성), `.../application/service/StrategyServiceTest.java`

**Interfaces:**
- Produces: `StrategyStatus.ARCHIVED`; `Strategy.isArchived(): boolean`; `Strategy.requireNotArchived(): void` — ARCHIVED면 `IllegalStateException("보관된 전략은 변경할 수 없습니다.")`.

- [ ] **Step 1: 실패 테스트 작성**
  - `StrategyTest.requireNotArchived_throws_for_archived`: ARCHIVED 전략 → `IllegalStateException`, 메시지 `"보관된 전략은 변경할 수 없습니다."`; ACTIVE·PAUSED는 예외 없음.
  - `StrategyServiceTest.pause_keeps_archived_status`: `StrategyStateWriter`(테스트가 이미 실제 인스턴스로 생성, `:156`)에 ARCHIVED 전략 → `stateWriter.pause(id)` 후 `verify(strategyPort, never()).save(any())`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.model.StrategyTest' --tests 'com.kista.tradingcore.trading.application.service.StrategyServiceTest'` → 컴파일 실패(ARCHIVED 없음).
- [ ] **Step 3: 구현**
  - `ARCHIVED("보관됨")` 추가.
  - `Strategy.isArchived()`, `requireNotArchived()`.
  - `StrategyStateWriter.pause`: `if (!strategy.isPaused() && !strategy.isArchived())`일 때만 PAUSED 저장. `StrategyPort.pause` default도 ARCHIVED면 저장하지 않는다.
  - 판정 치환(의미: "운영 중이 아님" = `!isActive()`): `TradingService:88` 분할 키를 `!ctx.strategy().isActive()`로, `:240`의 `status() != PAUSED`를 `strategy().isActive()`로, `CloseReportScope:16`·`CyclePositionPersistor:73`의 `paused`를 `!strategy.isActive()`로. 변수명 `paused`는 `notActive` 등으로 의미에 맞게 바꿔도 된다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.kista.tradingcore.trading.application.service.TradingServiceTest' --tests '*CloseReportScope*' --tests '*CyclePositionPersistor*'` 전부 PASS(치환 동작 보존).

### Task 2: 보관 유스케이스·엔드포인트

**Files:**
- Modify: `trading-core/.../trading/application/usecase/StrategyUseCase.java`
- Modify: `trading-core/.../trading/application/service/StrategyService.java`
- Modify: `trading-core/.../trading/application/service/StrategyStateWriter.java`
- Modify: `trading-core/.../trading/adapter/in/web/TradingCycleController.java` (pause 엔드포인트 옆)
- Test: `StrategyServiceTest`, `TradingCycleControllerTest`

**Interfaces:**
- Consumes: Task 1 `isArchived()`.
- Produces: `StrategyUseCase.archive(UUID strategyId, UUID requesterId): void`; `StrategyStateWriter.archive(UUID strategyId): void` (`@Transactional`, 재조회 후 `withStatus(ARCHIVED)` 저장); `PATCH /api/trading-cycles/{id}/archive` → 204.

- [ ] **Step 1: 실패 테스트 작성** (`StrategyServiceTest`, 기존 `pause_*`·`delete_*` 픽스처·stub 패턴 재사용)
  - `archive_saves_strategy_as_archived`: holdings 0, 당일 주문 없음, 취소 성공 → `strategyPort.save`가 `status == ARCHIVED`로 호출, `orderCancelService.cancelOpenOrders` 호출됨.
  - `archive_rejects_when_holdings_nonzero`: `cyclePositionPort.findLatestOneByStrategyId` → holdings 3 → `IllegalStateException("보유 수량을 모두 매도한 뒤 보관할 수 있습니다.")`, 취소 미호출.
  - `archive_allows_when_no_position`: `findLatestOneByStrategyId` → `Optional.empty()` → 보관 성공.
  - `archive_rejects_when_today_orders_not_reported`: 당일 `externalOrderId != null` 주문 존재 + `batchRunPort.isReported(today, id) == false` → `IllegalStateException("오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다.")`.
  - `archive_allows_when_today_orders_already_reported`: 위와 같되 `isReported == true` → 성공.
  - `archive_rejects_already_archived`: `"이미 보관된 전략입니다."`.
  - `archive_aborts_when_cancel_fails`: `CancelResult.retryableFailedCount() > 0` → `IllegalStateException`(Global Constraints 취소 실패 문구), ARCHIVED 저장 안 됨.
  - `archive_from_paused_succeeds`: PAUSED 전략도 보관 성공.
  - `delete_archived_strategy_soft_deletes`: ARCHIVED 전략 삭제 → 상태 저장 없이 `stateWriter.delete` 경로(소프트 삭제) 진행.
  - `TradingCycleControllerTest.archive_returns_204`: `PATCH /api/trading-cycles/{id}/archive` (csrf + UUID principal) → 204, `verify(tradingCycle).archive(id, USER_ID)`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*StrategyServiceTest' --tests '*TradingCycleControllerTest'` → 컴파일 실패.
- [ ] **Step 3: 구현 `StrategyService.archive`** (`@Transactional(propagation = NOT_SUPPORTED)`), 순서 고정:
  1. `findByIdOrThrow` + `accountPort.requireOwnedAccount`.
  2. `isArchived()` → "이미 보관된 전략입니다.".
  3. `cyclePositionPort.findLatestOneByStrategyId(id)`의 `holdings().signum() != 0` → 보유 거부(없으면 통과).
  4. `LocalDate today = LocalDate.now(clock)` (`TradingCloseScheduler:97`이 `findPausedWithReportableOrders`에 넘기는 날짜와 같은 기준). `orderPort.findByStrategyId(id, today, today)` 중 `externalOrderId() != null && !Order.UNKNOWN_LEG.equals(orderLeg)`가 있고 `!batchRunPort.isReported(today, id)`면 당일 주문 거부. `Clock`·`OrderPort`·`BatchRunPort`를 생성자에 추가(이미 있으면 재사용) — 생성자 변경으로 깨지는 테스트 생성 코드도 고친다.
  5. `orderCancelService.requireNoCryptoInFlight` → `stateWriter.pause` → `cancelOpenOrders`; `retryableFailedCount() > 0`이면 취소 실패 문구로 throw.
  6. `stateWriter.archive(id)`. `StrategyCapitalChangedEvent`는 발행하지 않는다. `log.info("[strategyId={}] 전략 보관", id)`.
- [ ] **Step 4: 컨트롤러** — pause 엔드포인트와 같은 형태(`@Operation(summary = "전략 보관")`, `@ResponseStatus(NO_CONTENT)`).
- [ ] **Step 5: 통과 확인** — Step 2 명령 PASS.

### Task 3: 보관 전략 변경 거부

**Files:**
- Modify: `StrategyService.java` (`pause:81`, `resume:98`, `update:177`)
- Modify: `VrReconfigureService.java:62` (`reconfigure`), `DcaConfigService.java:34` (`updateConfig`)
- Modify: `CapitalInjectionService.java` `lockActiveCycle` (~`:216`, ponytail 주석 제거하고 `owned.strategy().requireNotArchived()`)
- Modify: `ManualTradeCorrectionService.java:62` (`correctManualFills`, 전략 확보 직후)
- Modify: `StrategyStatusInternalController.java:29-43`
- Test: `StrategyServiceTest`, `VrReconfigureServiceTest`, `DcaConfigServiceTest`, `CapitalInjectionServiceTest`, `ManualTradeCorrectionServiceTest`, `StrategyStatusInternalControllerTest`

**Interfaces:**
- Consumes: Task 1 `requireNotArchived()`.

- [ ] **Step 1: 실패 테스트 작성** — 각 테스트 클래스에 ARCHIVED 전략으로 `<method>_rejects_archived_strategy` 1개씩: `IllegalStateException("보관된 전략은 변경할 수 없습니다.")` + 부수효과 없음(`save`/`cancelOpenOrders`/잠금 미호출 중 해당하는 것 `never()`). 대상: pause, resume, update, reconfigure, updateConfig, inject, withdraw(둘 다 `lockActiveCycle` 경유라 하나만으로 충분하면 inject 하나), correctManualFills.
  - `StrategyStatusInternalControllerTest`: (a) `status=ARCHIVED` 요청 → 400, `strategyUseCase` 상호작용 없음; (b) ARCHIVED 전략에 `status=ACTIVE` → 400(서비스 resume이 던지는 예외 전파 확인 또는 컨트롤러 사전 검사 — 어느 쪽이든 400).
- [ ] **Step 2: 실패 확인** — 위 테스트 클래스들 `--tests`로 실행 → FAIL.
- [ ] **Step 3: 구현** — 각 메서드에서 전략을 확보한 직후 `strategy.requireNotArchived()`(pause는 `isPaused()` 검사보다 먼저). 내부 컨트롤러 switch에 `case ARCHIVED -> throw new IllegalArgumentException("보관 상태로는 변경할 수 없습니다.");` 추가(전략이 이미 ARCHIVED인 경우는 서비스 가드가 막는다).
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS.

### Task 4: 목록 제외·`includeArchived`·통계 포함 잠금

**Files:**
- Modify: `StrategyUseCase.java`, `StrategyService.java` (`listByUserId:149`, `listByAccountId:163`)
- Modify: `TradingCycleController.java` (`GET /api/trading-cycles`)
- Modify: `TradingPreviewService.java:103`, `support/UnassignedCashCalculator.java:35`, `StrategyCreationService.java:194` (`validateUniqueTicker`)
- Test: `StrategyServiceTest`, `TradingCycleControllerTest`, `TradingPreviewServiceTest`, `UnassignedCashCalculatorTest`, `StrategyCreationService` 테스트, `StrategyPersistenceAdapterTest`

**Interfaces:**
- Produces: `StrategyUseCase.listByUserId(UUID userId, boolean includeArchived): List<StrategyDetail>` (기존 1-인자 시그니처 대체, 호출부·테스트 갱신); `GET /api/trading-cycles?includeArchived=false`(`@RequestParam(defaultValue = "false")`).

결정: 포트 메서드를 새로 만들지 않고 호출부에서 `.filter(s -> !s.isArchived())`로 거른다(spec "목록용 조회"의 구현 형태 — `findByAccountId(s)`는 그대로).

- [ ] **Step 1: 실패 테스트 작성**
  - `StrategyServiceTest.listByUserId_excludes_archived_by_default` / `listByUserId_includes_archived_when_requested`; `listByAccountId_excludes_archived`.
  - `TradingCycleControllerTest.listMine_passes_includeArchived`: 파라미터 없음 → `listByUserId(USER_ID, false)`, `?includeArchived=true` → `true`.
  - `TradingPreviewServiceTest.previewBatch_skips_archived`.
  - `UnassignedCashCalculatorTest.available_ignores_archived_deposit`: ARCHIVED 전략의 `usdDeposit`이 reserved에 합산되지 않음.
  - `validateUniqueTicker_ignores_archived`: 같은 종목 ARCHIVED 전략만 있으면 `DuplicateStrategyTickerException` 없음.
  - `StrategyPersistenceAdapterTest.findByAccountIds_includes_archived`(DB): ARCHIVED 전략 저장 후 `findByAccountIds`·`findByAccountId` 결과에 포함 — 통계 경로 잠금.
- [ ] **Step 2: 실패 확인** — 해당 클래스들 `--tests` 실행 → FAIL/컴파일 실패.
- [ ] **Step 3: 구현** — 위 5개 호출부에 필터. `listByUserId`는 `includeArchived`가 false일 때만 거른다. `listByAccountId`는 항상 거른다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS (`StrategyPersistenceAdapterTest`는 로컬 postgres 필요).

### Task 5: 문서 동기화

**Files:**
- Modify: `src/test/resources/architecture/flows.yml` (strategy lifecycle `:1681-1703`, pause-stop 흐름 `:886-960`)
- Modify: `docs/agents/modules/tradingcore/trading.md` (보관 규칙 한 단락)
- Modify: `docs/agents/code-quality-backlog.md` CQ-37 상태
- Test: `ArchitectureMapTest`

- [ ] **Step 1: flows.yml** — lifecycle `states`에 ARCHIVED, 전이 ACTIVE→ARCHIVED·PAUSED→ARCHIVED(조건: 보유 0·당일 미반영 주문 없음·미체결 취소 성공), ARCHIVED→삭제. pause-stop 흐름에 `archive` 단계(`code:` 참조는 `StrategyService` 등 존재하는 클래스만), `:890`·`:941`·`:945`의 "ACTIVE·PAUSED" 문구 갱신(관리자는 ARCHIVED 요청 400), `:936`·`:1703` 삭제 설명에 "통계에서도 빠짐 — 남기려면 보관".
- [ ] **Step 2: trading.md** — 보관 조건, 거부 목록(`requireNotArchived`), "목록은 호출부 필터 / 통계는 `findByAccountIds` 그대로" 규칙, 신규 상태 추가 시 `!= PAUSED` 대신 `isActive()`로 판정하라는 한 줄.
- [ ] **Step 3: backlog** — CQ-37 상태를 `done(2026-11-07, 커밋 해시는 최종 커밋 후 기입)`, UI 후속(kista-ui 세션: 보관 버튼·통계 화면 보관 섹션·삭제 문구·`includeArchived`) 한 줄.
- [ ] **Step 4: 검증** — `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → BUILD SUCCESSFUL. README.md에 전략 상태 언급 없음 재확인(`grep -n "PAUSED\|일시중지\|보관" README.md`).

### 최종(컨트롤러)

- [ ] 전체 테스트 1회: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`.
- [ ] 코드 리뷰(`/code-review` 또는 리뷰어 서브에이전트) → 결함 수정.
- [ ] 커밋 → spec·plan `git mv`로 archive 이동은 UI 작업까지 끝난 뒤(이 계획에서는 옮기지 않는다).
- [ ] kista-ui 세션용 요청 문구 작성(spec "UI 계약" 섹션 경로 + `git -C kista-api diff`).
