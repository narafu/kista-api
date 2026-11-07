# 전략 보관(ARCHIVED) 설계 — CQ-37

## 의도

전략을 소프트 삭제하면 그 전략의 사이클·원장이 통계(실현손익·승률·곡선)에서 소급해 사라진다. 사용자는 "기록으로 남길 끝난 전략"과 "치우려는 전략"을 구분하고 싶다. 그래서 `StrategyStatus.ARCHIVED`(보관)를 신설한다.

- 보관: 매매를 영구히 멈추고 목록에서 숨긴다. 통계에는 계속 남는다.
- 삭제: 지금 동작을 그대로 유지한다. 통계에서도 빠진다.

## 목표

- 보관은 되돌릴 수 없는 한 방향 전이다(보관 해제 없음).
- 보관된 전략은 어떤 경로로도 매매·상태 변경·설정 변경이 일어나지 않는다.
- 보관된 전략의 성과는 통계 화면 전부에 그대로 남는다.
- 보관된 전략은 통계 화면의 "보관된 전략" 섹션에서만 보이고, 거기서 삭제할 수 있다.

## 비목표

- 보관 해제(재활성화)
- 보유 중인 전략의 보관(보유분 이관·청산 자동화)
- 관리자 화면에서 보관 전략 별도 분리 표시(관리자 목록은 지금처럼 전부 보이고 상태 라벨만 "보관됨")
- 보관일 컬럼(`archived_at`) 신설 — 아래 "판단" 참고

## 안티 목표

- 보관된 전략이 배치·수동 실행·사이클 롤오버로 주문을 내거나 새 사이클을 만든다.
- 보관 상태가 pause·사이클 종료·정정 경로에서 PAUSED/ACTIVE로 조용히 덮어써진다.
- 목록을 숨기려고 `StrategyPersistenceAdapter.findByAccountIds`에 필터를 넣어 통계에서도 빠진다.

## 현재 상태(조사 결과)

- 매매 진입점은 모두 ACTIVE 한정이다: `StrategyJpaRepository.findAllActiveStrategies`(`status='ACTIVE'`, Open/Close 스케쥴러·`CryptoDcaRunner`), `ManualTradingService:61`(`isActive()`). 보관 전략이 지금 매매될 경로는 없다.
- 통계는 상태 필터 없이 `findByAccountIds` → 사이클 → 원장을 읽는다(`TradingStatsService.loadCycles:235`, `InvestmentPointsQueryService:58`). ARCHIVED는 자동으로 통계에 포함된다.
- 삭제(`StrategyService.delete`)는 `stateWriter.pause` → 미체결 취소(실패 시 400, 상태는 PAUSED로 남음) → 원장·사이클·전략 소프트 삭제 순서다.
- DB `strategy.status`는 `VARCHAR(20)`, CHECK 없음 → 마이그레이션 불필요.
- `MetaController`는 `StrategyStatus.values()`로 meta를 만든다 → 상수만 추가하면 UI 라벨이 자동 공급된다.

## 설계

### 도메인

- `StrategyStatus.ARCHIVED("보관됨")` 추가(`com.kista.shared.kernel`).
- `Strategy.isArchived()` 추가.

### 보관 API

`PATCH /api/trading-cycles/{id}/archive` (기존 `/pause`·`/resume`과 같은 패턴, 응답도 같은 형태).

`StrategyService.archive(requesterId, strategyId)` 순서:
1. 소유권 검증.
2. 이미 ARCHIVED면 400 ("이미 보관된 전략입니다.").
3. 최신 사이클(종료 여부 무관)의 최신 원장 `holdings`가 0이 아니면 400 ("보유 수량을 모두 매도한 뒤 보관할 수 있습니다."). 원장이 없으면 0으로 본다. 크립토 DCA도 보유를 `cycle_position`에 기록하므로(`CryptoPortfolioService`가 원장 평단을 읽음) 같은 판정을 쓴다.
3-1. 진행 중 사이클에 당일 접수 주문(`findPausedWithReportableOrders`와 같은 조건: `trade_date = 오늘`, `external_order_id IS NOT NULL`, `order_leg <> UNKNOWN`)이 있으면 400 ("오늘 접수된 주문이 마감 반영된 뒤 보관할 수 있습니다."). 이유: 당일 체결분은 마감 배치가 원장에 반영하기 전이라 원장 `holdings`=0이어도 실제로는 보유 중일 수 있고, 마감 리포트는 ACTIVE·PAUSED 전략만 처리해서 ARCHIVED가 되면 그 체결이 원장에 영영 반영되지 않는다.
4. `requireNoCryptoInFlight` (delete와 동일).
5. `stateWriter.pause` → 미체결 주문 취소. 재시도 대상 취소가 하나라도 실패하면 400으로 중단한다(delete와 같은 정책, 상태는 PAUSED로 남는다).
6. 상태를 ARCHIVED로 저장.

ACTIVE·PAUSED 어느 상태에서든 보관할 수 있다.

### 보관 후 거부되는 변경(400 "보관된 전략은 변경할 수 없습니다." 계열)

- pause, resume (`StrategyService.pause:85`, `resume:101`)
- 수정 `PUT /{id}`, `vr-config`, `dca-config`
- 자금 투입·인출 (`CapitalInjectionService` — `:216`의 "두 값뿐이라 상태 검사 생략" 가정 제거)
- 수동 정정 (`ManualTradeCorrectionService`)
- 수동 실행은 이미 `isActive()`로 거부됨
- 관리자 상태 변경 (`StrategyStatusInternalController:41`, `AdminAccountController:77`): 요청 값 ARCHIVED는 거부하고, 대상 전략이 ARCHIVED여도 거부한다.

삭제만 허용한다. 삭제 경로의 `stateWriter.pause`는 ARCHIVED를 PAUSED로 바꾸지 않는다(아래).

### 덮어쓰기 방어

- `StrategyStateWriter.pause`, `StrategyPort.pause`(default): ARCHIVED면 상태를 바꾸지 않는다(no-op). 이 경로는 delete·계좌 삭제 캐스케이드(`AccountOpenOrderCanceller`)가 공유하며, 주문 취소는 계속 수행한다.
- `CycleRotationService`·`CycleCloser`·`ManualTradeCorrectionService`의 PAUSED 기록: 보관 전략은 배치에 들어오지 않고 정정도 거부되므로 도달하지 않는다. 별도 코드 변경 없음(빌더 판단으로 방어가 싸면 추가 가능).

### `!= PAUSED` 분기를 `isActive()` 기준으로

잠재 함정 4곳 — ARCHIVED가 들어오면 "운영 중"으로 취급돼 사이클 롤오버가 일어난다:
- `TradingService.java:88` (close 배치 분할), `:240` (`resumeCloseReport`)
- `CloseReportScope.java:16`, `CyclePositionPersistor.java:73` (`paused = status == PAUSED` → `!isActive()` 의미로)

동작 보존: 현재 입력은 ACTIVE·PAUSED뿐이라 결과가 같다. 기존 테스트로 확인한다.

### 목록·조회에서 제외

`StrategyPort`에 목록용 조회(보관 제외)를 따로 둔다. **`findByAccountId(s)`는 그대로 두고** 다음 호출부만 목록용으로 바꾼다:
- `StrategyService.listByUserId:154`, `listByAccountId:165` (사용자 목록)
- `TradingPreviewService.java:103` (`previewBatch`)
- `UnassignedCashCalculator:35` (미배정 현금 — 보관 전략은 현금을 점유하지 않음)
- `StrategyCreationService.validateUniqueTicker:194` (보관 후 같은 종목 새 전략 허용)

그대로 두는 호출부: 통계(`TradingStatsService`, `InvestmentPointsQueryService`), 관리자 조회(`TradingInternalQueryController:52,58`), `AccountOpenOrderCanceller`, `MockSimulationDataAdapter`, `CryptoPortfolioService`(보관 시 보유 0이라 영향 없음).

`GET /api/trading-cycles/{id}` 단건 조회는 보관 전략도 반환한다(상태 ARCHIVED).

### 보관 전략 조회(통계 화면용)

`GET /api/trading-cycles?includeArchived=true` — 기본값 false는 지금과 같은 목록(보관 제외). true면 보관 포함 전체를 반환한다. 통계 화면은 이 한 호출로 (1) 종목 필터 드롭다운(보관 종목 포함), (2) "보관된 전략" 섹션(`status == ARCHIVED`)을 만든다.

### 통계 캐시

보관은 사이클·원장을 바꾸지 않으므로 `StrategyCapitalChangedEvent`를 발행하지 않는다. 삭제는 지금처럼 발행한다.

## UI 계약 (kista-ui, UI 세션에서 구현)

- meta `strategyStatuses`에 `ARCHIVED`("보관됨")가 추가된다. 라벨은 meta로 읽는다.
- 하드코딩 `'ACTIVE' | 'PAUSED'` 갱신: `entities/admin/model/types.ts:36`, `shared/lib/api-types.ts`(재생성).
- 전략 상세(`StrategyDetail`·`DcaStrategyDetail`): 삭제 버튼 옆에 "보관" 버튼. 보유 수량이 0이 아니면 비활성 + "보유 수량을 모두 매도한 뒤 보관할 수 있습니다." 안내(최종 판정은 서버).
- 보관 확인 문구: "{ticker} 전략을 보관하시겠습니까? 매매가 멈추고 목록에서 사라지지만 성과는 통계에 남습니다. 보관은 되돌릴 수 없습니다."
- 보관 성공: 계좌 상세로 이동 + 토스트, 전략 목록 쿼리 무효화.
- 삭제 확인 문구(`DeleteStrategyDialog.tsx:40`): 기존 문장 뒤에 "통계에서도 이 전략의 성과가 사라집니다. 통계를 남기려면 보관하세요." 보관된 전략 삭제 시에는 두 번째 문장("통계를 남기려면 보관하세요.")을 뺀다. 사용자 문구에서는 "기록" 대신 "통계"라는 말을 쓴다.
- 상세 화면에는 보관 버튼과 삭제 버튼을 둘 다 두고 각각 바로 동작한다(삭제→보관 2단계로 합치지 않는다 — 보유 중인 전략은 보관할 수 없어 상세에서 지울 길이 없어지기 때문).
- 통계 화면: `includeArchived=true` 목록으로 종목 필터를 만들고(`CyclePerformanceList.tsx:56`), "보관된 전략" 섹션(종목·유형·계좌 + 삭제 버튼)을 추가한다.

## 문서 동기화

- `flows.yml`: strategy lifecycle(`:1681-1703`)에 ARCHIVED 상태와 전이(ACTIVE/PAUSED→ARCHIVED, ARCHIVED→삭제) 추가. pause-stop 흐름(`:886-960`)에 archive 단계 추가, `:890`·`:941`·`:945`의 "ACTIVE·PAUSED" 문구 갱신, `:936`·`:1703` 삭제 설명에 "통계에서도 빠짐, 보관은 유지" 반영.
- `README.md`: 전략 상태 언급 없음 → 변경 불필요(구현 후 재확인).
- `docs/agents/code-quality-backlog.md` CQ-37 상태 갱신, `modules/tradingcore/trading.md`에 보관 규칙(보관 조건·거부 목록·목록용 조회 vs 통계용 조회 분리) 한 단락.

## 테스트

- `StrategyService.archive`: 보유>0 거부, 당일 접수 주문 있으면 거부, 이미 보관 거부, 취소 실패 시 중단, 성공 시 ARCHIVED.
- 보관 후 pause·resume·update·vr/dca-config·자금 투입/인출·정정·관리자 상태 변경 거부.
- `StrategyStateWriter.pause`가 ARCHIVED를 유지하면서 주문 취소는 수행.
- 목록 제외(listByUserId·previewBatch·미배정 현금·중복 종목)와 통계 포함(`TradingStatsService` 경로)을 각각 고정. 특히 "통계는 보관 전략 포함"을 테스트로 잠근다.
- `isActive()` 치환 4곳은 기존 테스트 통과로 동작 보존 확인.
- persistence: 목록용 조회가 ARCHIVED를 제외하는 DB 테스트.

### 라우팅·배포

- 보관 엔드포인트와 `includeArchived`는 기존 `/pause`·`/resume`과 같은 trading-core `TradingCycleController`에 둔다. UI가 지금 그 경로를 호출하는 방식 그대로라 root 프록시·`shared.contract` 변경은 없다(`StrategyResponse.status`가 `StrategyStatus`라 상수만 늘어난다).
- 관리자 상태 변경 거부는 trading-core `StrategyStatusInternalController`가 SSOT다(switch에 ARCHIVED 분기 추가 또는 사전 검사). root `StrategyStatusRequest`는 지금처럼 ACTIVE·PAUSED만 받는다.
- 배포 순서: API 먼저. ARCHIVED는 UI 보관 버튼이 생겨야 처음 생기므로 그 전엔 UI가 볼 일이 없다.
- `strategy(account_id, ticker)` DB 유니크 제약은 없다(검사는 `validateUniqueTicker` 애플리케이션 레벨뿐) → 보관 후 같은 종목 재등록에 마이그레이션 불필요.

## 판단(Claude가 정한 것)

- 당일 접수 주문이 있는 전략은 보관을 거부한다(위 3-1). 매매한 날은 다음 날 보관할 수 있다.

- 보관 API는 `PATCH /{id}/archive`, 보관 조회는 `includeArchived` 쿼리 파라미터.
- 보관일 컬럼을 만들지 않는다. 보관 섹션은 종목·유형·계좌만 보여 준다(대화 중 "보관일"을 언급했으나 마이그레이션·필드 추가 비용 대비 가치가 작아 뺌).
- 사이클 종료·정정 경로의 PAUSED 기록은 도달 불가로 판단해 코드 변경 없음.
- 관리자 목록은 보관 전략을 그대로 보여 준다.

## 빌더에게 맡기는 것

- 목록용 포트 메서드 이름과 JPQL 형태.
- 거부 예외 메시지의 정확한 문구("~니다." 규칙 준수).
- 보유 수량 판정에 쓸 기존 포트 메서드 선택(최신 원장 조회).
