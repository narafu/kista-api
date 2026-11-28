## com.kista.tradingcore.trading (`:trading-core`)

- 매매·VR 공식·VR 로직을 건드리기 전 `docs/agents/modules/tradingcore/trading-formulas.md`를 Read한다(자동 로드 안 됨).
- DCA 작업 시 `docs/agents/modules/tradingcore/dca.md`를 Read한다(자동 로드 안 됨).
- 매매·스케쥴러·주문 흐름은 `docs/agents/workflow.md` 인덱스, 흐름 원본은 `src/test/resources/architecture/flows.yml`.
- 결정(유지·기각) 목록은 `docs/agents/decisions.md` — 구조 변경 제안 전에 클래스명으로 grep한다.

### 구조 규칙
- 배치·프리뷰·리포트 경로는 `Account` 대신 `TradingAccount` 투영을 쓴다. 소유권 검증이 필요한 요청 경로만 `Account`를 받고 경계에서 변환한다 (가드: `trading_batch_path_must_not_carry_account_aggregate`)
- 리스너 FQCN·메서드명은 EPR `listener_id`다 — 이동·개명 시 `docs/agents/docker-infra.md` 런북을 따른다

### VR
공식·bootstrap·가격 캡·롤오버·재설정 규칙은 `trading-formulas.md` "VR 공식"이 SSOT.
- 보유 0이어도 사이클을 유지하고(`endsCycleOnLiquidation()=false`) `cycleSeedType=NONE`을 강제한다 — 사이클 교체는 롤오버가 맡는다
- 일반 시드 수정은 저장 전에 거부하고 VR 재설정(`PUT /api/trading-cycles/{id}/vr-config`)을 쓴다

### 투자금 추가·인출·시드 수정
- 대상 PRIVACY·INFINITE·DCA. 사이클을 유지하고, 사이클 잠금 후 재조회한 최신 포지션 행을 in-place 갱신하며 `startAmount`도 함께 바꾼다. 원금 변화는 같은 트랜잭션에서 `cycle_capital_event`(INJECT/WITHDRAW/RESET)에 남긴다(통계 원금 → `trading-formulas.md` "통계 원금")
- 오늘 postClose 이후 리포트 마커 전이고 리포트 대상 주문이 있으면 400(`CloseReportScope`). 수동 체결 보정도 같은 창을 거절한다
- 인출 가능액 = 장부 예수금 − 체결 전 BUY 약정 − 리포트 전 취소 BUY의 기록 체결분(`StrategyLedgerBudget`). 강제 매도 없이 다음 주문 계산부터 반영한다. 인출 후 `startAmount ≤ 0`이거나 INFINITE 최소 시드 미만이면 400
- 시드 수정은 비-VR 진행 중 사이클의 최신 포지션 `holdings=0`일 때만 허용, 종료 사이클이면 400(재개 후 수정)
- 응답 `ledgerDeposit`이 UI 최대 인출 기준이다(DB `cycle_position.deposit`·도메인 `deposit`은 이름 유지). 계좌 통화 자릿수로 절사(`Currency.truncate`)해 표시액을 그대로 요청해도 통과하게 하고, `NO_WITHDRAWABLE_BALANCE` 판정도 이 내림값을 쓴다(인출 서비스 내부 계산은 원값)

### 상태 플래그·검증 엔드포인트
- 행위 판정 SSOT: `StrategyAction.supports(type)`(종류별 지원 동작)·`StrategyActionJudgment.of(detail)`(`allowedActions`/`blockedActions{action,reasonCode}`). 불변식 allowed ∪ blocked = 지원 전체. 서비스 가드(`requireSupportedType` 등)도 `supports()`를 쓴다. 미리보기 `warnings`는 `OrderPreviewWarning`
- 한계: 마감 리포트 창·당일 주문 반영 전 보관·재개 시 1주 미만 잔액은 판정 밖이라 allowed여도 400 가능
- 검증 POST `trading-cycle-validations`·`update-validations`·`vr-config-validations`: 통과·거절 모두 200 `{fieldErrors[{field,detail}], warnings[{code,detail}], derived}`. 등록·수정·재설정과 같은 `plan()`을 저장 없이 공유(거절 순서 동일). 경고 `AVAILABLE_CASH_EXCEEDED`(금액은 계좌 통화 `Currency.display`). `update-validations`는 시드 수정(`newSeed`)이면 등록과 같은 시드 미리보기(fail-open)로 `basePrice`·`minSeed`·`skipReason`·`availableCash`를 싣는다(INFINITE `minSeed`는 활성 버전 분할 수 기준, PRIVACY 최소 시드·`NO_PRIVACY_BASE`는 거절하지 않음 — Q-35)

### Account ↔ Strategy 분리
- 계좌(account 모듈)와 전략은 별도 aggregate다. `Account`에 type/status/ticker/multiple을 다시 두지 않는다 — 전략 속성은 `Strategy` 소유
- `CyclePosition`은 체결마다 append되는 스냅샷이다(dedup/UNIQUE 없음). 투자금·시드 변경만 최신 행을 in-place로 바꾼다
- 계좌당 종목 1개 전략(DCA 포함 전 전략 공통, 중복 409). 한 종목의 매수·매도는 DCA 한 전략의 규칙으로 표현한다

### 전략 보관 (ARCHIVED)
- ACTIVE·PAUSED → ARCHIVED 한 방향이다(해제 없음). 보유 0·당일 접수 주문 마감 반영·미체결 취소 성공을 확인한 뒤 저장하고, 실패하면 PAUSED로 남는다
- 보관 전략 변경 거부: `Strategy.requireNotArchived()`를 pause·resume·update·VR 재설정·DCA 설정·투자금 추가·인출·수동 체결 보정에서 호출하고, 상태 API(`StrategyStatusInternalController`)는 ARCHIVED 요청과 보관 전략 ACTIVE 요청을 400으로 막는다. 삭제는 보관 전략도 허용한다(소프트 삭제, 통계에서 빠짐)
- 목록·미리보기 제외는 호출부 필터(`!isArchived()`, `includeArchived`)로 한다. `StrategyPort.findByAccountId(s)`에 필터를 넣지 않는다. 계좌당 종목 중복 검사만 보관을 제외한다
- 상태 쓰기는 `save(withStatus(...))`가 아니라 조건부 UPDATE(`updateStatusUnlessArchived`·`archiveIfPaused`)로 한다
- 마감 리포트는 ARCHIVED도 PAUSED처럼 잡아 보관 직전 접수분 체결을 원장에 남긴다(사이클 종료만 기록, rotation·알림·VR 롤오버 없음)
- 새 상태를 추가하면 `status != PAUSED`가 아니라 `isActive()`로 판정한다 — 배치·정산·리포트가 ARCHIVED를 매매 대상으로 잡지 않게

### 전략 원장 상한·order_leg
- 원장 상한 규칙 → `docs/agents/workflow-ledger-price.md` "스케쥴러 실행 흐름", `order_leg`·슬롯 → `docs/agents/workflow-batch.md`·`docs/agents/workflow-orders.md`
- orders 조회 조건을 바꾸면 `idx_orders_cycle_date_timing_status` 등 인덱스 prefix(`db/migration-trading` baseline)를 함께 확인한다

### 런타임 매매 정책 적용
정책 원본은 `trading.trading_runtime_settings`(`TradingPolicyService` 소유), 엔드포인트 규칙 → `modules/api/admin.md` "런타임 설정 API 규칙".
- `brokers.<broker>.enabled=false`: 신규 계좌 등록·연결 테스트만 외부 호출 전 400. 기존 계좌의 조회·수정·매매는 영향 없음
- `strategies.<type>`: 신규 등록에만 적용한다(`enabled=false`면 400, ticker·INFINITE divisionCount·VR recurringMode/bandWidth/intervalWeeks의 생략 기본값·허용/고정값 검증). 기존 전략 수정·실행에 소급하지 않는다
- INFINITE `divisionCount=null`은 미입력이고 `InfiniteCreationResolver`가 런타임 기본값으로 정한다(명시 0은 400). VR `recurringAmount` 생략은 `VrCreationResolver`가 정책 기본 `recurringMode`로 정한다(HOLD면 0, 그 외 400)
- VR `recurringMode`는 `recurringAmount` 부호(DEPOSIT/HOLD/WITHDRAW)로만 검증하고 크기는 VR 자산 규칙에 맡긴다. `recurringMode.customizable=false`면 기본값·유일 허용값이 모두 `HOLD`여야 한다

## 테스트 패턴

→ `trading-testing.md` (테스트 디렉토리 작업 시 자동 로드)

### 소유권 검증
- `account.verifyOwnedBy(requesterId)` 불일치 시 `SecurityException`(403은 `ProblemDetailMappings.GENERIC`). 서비스는 `AccountPort.requireOwnedAccount(accountId, requesterId)`(`findByIdOrThrow` + `verifyOwnedBy`)를 쓴다
- 전략·사이클 소유권은 `strategy.accountId()`가 요청 계좌와 같은지로 본다(예: `OwnedStrategy.load`)
- 전략 소유권 + 계좌 조회를 함께 하는 trading 서비스는 `OwnedStrategy.load(...)`(`application/service/support`)를 공용 통로로 쓴다. stats는 모듈 경계상 별도(`InvestmentPointsQueryService`)
