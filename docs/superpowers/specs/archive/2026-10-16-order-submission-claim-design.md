# 주문 접수 선점(SUBMITTING) 설계 — DB↔증권사 불일치 차단

작성일: 2026-10-16 · 대상: `kista-api` `:trading-core`(+ `:shared` enum) · 후속 감사 항목 2·5 (메모리 `project_batch_audit_followups`)

## 1. 왜

주문 접수 경계에서 DB와 증권사 상태가 갈라지는 두 경로가 있다.

- **(2) 접수 성공 후 PLACED 기록 실패**: `TradingOrderExecutor.placeEach`(`TradingOrderExecutor.java:119`)는 증권사 접수(`:133`) 뒤 `markPlacedWithRetry`(`:150`)로 PLACED를 기록한다. 이게 실패하면 DB=PLANNED로 남고 `[DB 불일치]` 알림(`:154-159`)만 나간다. 같은 거래일 재실행(수동 실행·`TradingBatchResumer` 재개 `:71-80`/`:94`)이 `findPlannedByCycleAndDate`(`:66`)·`findAtOpenPlannedByCycleAndDate`(`:51`)로 이 PLANNED를 다시 집어 **중복 접수**한다. SIGKILL·OOM으로 HTTP 도중 프로세스가 죽어도 같은 상태가 남는다.
- **(2') 모호한 실패를 FAILED로 기록**: HTTP 예외는 종류를 가리지 않고 `markFailed`(`:139`)한다 — 소켓 끊김·읽기 타임아웃·인터럽트처럼 증권사가 이미 접수했을 수 있는 경우도 FAILED가 된다. 슬롯 점유 판정은 PLANNED·PLACED만 보므로(`OrderPersistenceAdapter.java:148,158`) 재실행이 같은 leg를 새 PLANNED로 만들어 **중복 접수**한다(`workflow.md` "병렬 접수 인터럽트 리스크"가 기술한 경로).
- **(5) 접수 도중 취소**: 일시정지·전략/계좌 삭제·탈퇴 cascade(`StrategyService:69,89`, `AccountOpenOrderCanceller:26`, `UserTradingDataEraser:44`)는 `OrderCancelService.cancelOpenOrders`로 모인다. PLANNED는 `deletePlanned`(단일 DELETE, `OrderJpaRepository:27`)로 지우고 PLACED만 증권사 취소한다. 접수 HTTP 진행 중인 행은 아직 PLANNED라 그냥 지워지고, 접수 쪽이 응답 뒤 `markPlaced` 경합을 보고서야 증권사 취소한다(`cancelRacedOrder`, `:175`). 게다가 `placeEach`는 루프 시작 때 읽은 PLANNED 스냅샷을 건마다 다시 확인하지 않으므로, VR 사다리처럼 KIS 350ms 게이트를 여러 번 거치는 루프 도중 취소가 오면 **이미 지워진 남은 N건까지 전부 "접수 → 경합 → 사후 취소"**를 거친다. 그 사이 장중 지정가가 체결될 수 있다. 사후 취소가 실패해도 취소를 요청한 쪽(`CancelResult`)은 성공으로 안다.

## 2. 목표 / 비목표 / 안티목표

**목표**
- 증권사 응답을 DB가 반영하지 못한 주문을 어떤 재실행 경로도 다시 접수하지 않는다.
- 취소가 접수 루프와 겹칠 때 아직 HTTP를 보내지 않은 주문은 보내지 않는다.
- 접수 진행 중인 주문을 만난 취소는 결과를 확인한 뒤 판정해 `CancelResult`가 사실과 맞는다.
- Toss는 멱등키로 같은 주문의 재요청을 안전하게 만들고, 짧은 창 안에서는 자동 대사한다.

**비목표**
- KIS 당일 주문 목록 근사 매칭 대사(후속 — 실계좌 확인 전 신뢰 불가).
- 진행 중인 HTTP 1건의 창 제거(어떤 설계로도 불가능 — 보낸 요청은 회수할 수 없다).
- 잔류 SUBMITTING 정정용 관리자 API/UI(이번엔 운영 런북 SQL).
- Toss `sellableQuantity` 이중 차감 건(후속 1번, 실측 전 손대지 않음). 매매 공식(`trading-formulas.md`) 불변.

**안티목표** (기술적으로 동작해도 실패로 본다)
- 증권사에 접수됐을 수 있는 주문을 자동으로 재접수하는 경로가 하나라도 남는 것.
- 그레이스풀 재기동(게이트 대기 중 인터럽트 등 송신 전 확정 실패)이 SUBMITTING 잔류를 양산해 매번 관리자 수동 정정이 필요해지는 것.
- 예산·판매가능수량 계산이 SUBMITTING을 빼먹어 같은 예수금·수량을 이중 배정하는 것.

## 3. 결정된 설계 (사용자 선택: A + C(b) + Toss clientOrderId)

### 3.1 새 상태 `OrderStatus.SUBMITTING` ("접수 중")

`com.kista.shared.kernel.OrderStatus`에 추가. DB는 VARCHAR(20)·CHECK 제약 없음(`migration-trading/V1__init.sql`) → **마이그레이션 불필요**.

전이:
- `PLANNED → SUBMITTING`: 접수 HTTP 직전 건별 선점(3.2).
- `SUBMITTING → PLACED`: 접수 성공 + 기록.
- `SUBMITTING → FAILED`: **확정 거절**(3.3)만.
- `SUBMITTING` 유지: 결과 불명(3.3) — 자동 재접수 대상 아님. Toss 자동 대사(3.5) 또는 운영 정정(3.7)으로만 벗어난다.
- `SUBMITTING → CANCELLED`는 없다 — 취소는 결과가 확정된 뒤(PLACED)에만 진행한다(3.6).

### 3.2 접수 직전 건별 선점

`placeEach`의 각 주문에 대해 HTTP 전에 짧은 트랜잭션으로 `OrderPort.markSubmitting(orderId)` — 기존 `markPlaced`와 같은 `lockOrConflict` + PLANNED 요구 패턴. 경합(`OrderStateConflictException` — 그 사이 삭제·취소·재캡 취소)이면 **HTTP 없이 건너뛴다**(로그만). 이후:
- 성공 → `markPlaced`(전제 상태를 PLANNED에서 SUBMITTING으로 변경, 같은 접수번호면 멱등 성공 유지, 1회 재시도 유지). 기록 실패 → SUBMITTING 잔류 + `[DB 불일치]` 알림 문구를 "자동 재접수되지 않음, 런북으로 정정"으로 갱신.
- 확정 거절 → `markFailed`(전제 상태 SUBMITTING).
- 결과 불명 → 상태 유지 + 관리자 알림 `[접수 결과 불명]`(orderId·계좌·종목·방향·예외 요약).

`markPlaced` 경합 처리(`cancelRacedOrder`)는 SUBMITTING을 거치면 정상 경로에선 발생하지 않지만, 방어로 남긴다.

### 3.3 예외 분류 — 확정 거절 vs 결과 불명

기본값은 **결과 불명**(보수적). 다음만 확정 거절로 본다:
- KIS: 응답 수신 후 `rt_cd != "0"`(`KisOrderApi.java:62`). HTTP 오류 응답(4xx·5xx 불문)이라도 **본문이 KIS 오류 형식(`msg_cd` 존재 — EGW00201 등)으로 파싱되면** 확정 거절(증권사가 요청을 처리하고 거부한 것). 본문 없는 4xx도 확정, 본문 없는/비KIS 5xx(게이트웨이)는 불명. **송신 전 실패** — 호출 간격 게이트 대기열 초과(`KisHttpClient:160`)·게이트 대기 중 인터럽트(`:172`)·토큰 확보 실패(`:109` `getToken`은 try 밖).
- 401 재시도 경로(`:115-126`): 재발급 실패는 송신 전이라 확정, 재시도 HTTP의 `RestClientException`(`:124`)은 다른 HTTP 예외와 같은 규칙(HTTP 응답이면 위 기준, I/O면 불명)으로 분류하도록 감싸기를 나눈다.
- Toss: HTTP 4xx 응답 중 409 `request-in-progress` 제외(그건 진행 중 = 결과 불명), 응답 `orderId` 없음, 송신 전 실패(토큰 확보 실패 등).
- `BrokerCredentialException`·`BrokerRateLimitException`(토큰 발급 단계 예외, `BrokerApiException` 하위 아님 — `KisAuthApi`·`TossAuthApi`가 던짐)은 **타입으로 확정 거절** 분류. 안 그러면 키 만료 계좌가 매 배치 SUBMITTING을 양산한다(안티목표 2).
- 결과 불명: `RestClientException`(I/O·타임아웃), 본문 없는 5xx, 송신 후 인터럽트, 위에 없는 예외 전부.

표현: 벤더 중립 `BrokerApiException`에 "증권사가 접수하지 않았음이 확실" 플래그를 두고 어댑터가 위 기준으로 세팅한다(이름·형태는 구현자 재량). 소비자(trading)의 판정은 "플래그가 선 `BrokerApiException` 또는 `BrokerCredentialException`/`BrokerRateLimitException`이면 확정, 그 외 불명" 한 곳에 둔다. [내 판단: msg_cd 기반 분류·자격증명 예외 타입 분류]

### 3.4 SUBMITTING을 "열린 주문"으로 취급하는 곳

| 판정 | 위치 | 변경 |
|---|---|---|
| 슬롯 점유·재계산 skip·수동 실행 이중 실행 | `findPlannedOrPlaced*`(`OrderPersistenceAdapter:148,158`) → `TradingCandidatePlanner:139,288,310`, `ManualTradingService:70,112,142`, `TradingPreviewService:95` | 상태 집합에 SUBMITTING 추가 |
| BUY 예약 합계 | `sumPlannedBuyAmount…`(`OrderJpaRepository:61-68`) → allocator·재캡·수동·재주문 예산·미리보기 | `IN ('PLANNED','SUBMITTING')` — 증권사가 이미 차감했을 수 있어 이중 차감(보수적, 기존 주석과 같은 방향) |
| SELL 예약 합계 | `:75-83` → allocator·`DcaRoundPlanner`·판매 충분성 시뮬레이터 | `IN ('PLANNED','SUBMITTING','PLACED')` |
| 접수 대상 | `findPlannedByCycleAndDate`·`findAtOpenPlanned…`, 재캡 대상 | 변경 없음(PLANNED만) — SUBMITTING은 재접수·재캡하지 않는다 |
| 일괄 삭제 | `deletePlannedByCycleAndDate` | 변경 없음(PLANNED만) |
| PLACED 전용 조회 | `findPlacedByCycleAndDate` → `TradingService:224`(리포트 대상에 선접수분 합류), `OrderCancelService:75`, `MockSimulationDataAdapter:47` | 변경 없음 — 리포트는 3.8, 취소는 3.6이 SUBMITTING을 따로 다룬다. Mock은 결과 불명이 생기지 않는다(인메모리 접수) |

메서드 이름(`…PlannedOrPlaced…`)은 바꾸지 않고 주석으로 의미를 갱신한다(호출부 churn 방지 — 구현자 판단으로 rename 가능).

### 3.5 Toss `clientOrderId` 멱등 + 짧은 창 자동 대사

- `OrderInstruction`에 `clientOrderId`(String) 추가, 값은 `orders.id` UUID 문자열(정확히 36자, Toss 허용 패턴 `^[a-zA-Z0-9\-_]+$` 안). Toss는 요청 body에 싣고 KIS·Mock은 무시한다.
- 근거(Toss 공식 openapi.json `OrderCreateRequest.clientOrderId`, FAQ): 10분 내 같은 키·같은 내용 재요청은 원 결과를 재반환, 원 요청 처리 중이면 409 `request-in-progress`, 내용이 다르면 422 `idempotency-key-conflict`. 10분 이후엔 새 주문. 목록/상세 응답(`Order` 스키마)에는 `clientOrderId`가 없어 사후 조회 불가.
- 대사 시점: `placeOrders`·`placeAtOpenOrders` 진입 시(= 재실행·재개·수동 실행 경로) 해당 사이클·거래일의 SUBMITTING을 조회한다.
  - 선점 후 **2분 미만**: 다른 스레드가 접수 중일 수 있으므로 건드리지 않는다.
  - **2분 이상 ~ 9분 미만**이고 Toss 계좌: 저장된 내용 그대로 같은 `clientOrderId`로 재요청 → 원 `orderId`가 오면 `SUBMITTING → PLACED`, 확정 거절이면 FAILED, 409·불명이면 유지.
  - 그 밖(KIS, Toss 9분 이상): 유지 + `[접수 결과 불명]` 관리자 알림(실행마다 1회, orderId 나열).
- 재요청은 **원 요청이 도달하지 않았으면 새 주문이 된다**(Toss는 그 키를 처음 본다). 이 경로는 접수 경로 안에서만 돈다 — 배치·재개(ACTIVE 사이클만, 재개는 접수 마감 전만)·수동 실행(ACTIVE 검증 후)이 호출하므로 "원래 의도한 주문을 지금 접수"와 같은 효과다. 그래도 재요청 직전에 **해당 주문 행이 아직 SUBMITTING인지(행 잠금 재확인)와 주문 거래일 = 호출 경로의 거래일**만 확인한다(추가 시간대 검증 없음 — 2~9분 창은 같은 배치 세션 안). [내 판단]
- 취소 경로(3.6)는 재요청 대사를 **하지 않는다** — 취소하려는 주문을 새로 접수할 수 있어서다.
- 선점 시각은 `orders.updated_at`(`BaseAuditEntity` `@LastModifiedDate`, SUBMITTING 전이가 갱신)을 쓴다 — 별도 컬럼·마이그레이션 없음. SUBMITTING 동안 다른 갱신 경로가 없으므로 선점 시각과 같다.
- 9분 상한은 Toss 10분 키 만료에 1분 여유. 시각 판단은 DB `updated_at`과 앱 `Instant.now()` 비교이므로 시계 차이 여유를 포함한다.

### 3.6 취소가 SUBMITTING을 만날 때 — 유한 대기 후 판정 (C(b))

- `cancelOpenOrders`: PLANNED 삭제 후 해당 사이클·거래일에 SUBMITTING이 있으면 최대 **60초** 폴링(짧은 간격, `Thread.sleep` — Virtual Thread)으로 상태가 바뀌길 기다린다. PLACED가 된 건은 기존 PLACED 취소 루프에 합류, FAILED는 무시, 30초 뒤에도 SUBMITTING인 건은 **재시도 가능한 실패**(`failedCount`, 자격증명 실패 아님)로 집계 → 삭제·탈퇴는 기존 규칙대로 막히고 관리자 알림(`OrderCancelFailedEvent`)에 포함.
- `cancelOrder`(개별): SUBMITTING이면 같은 대기 → PLACED면 증권사 취소, 여전히 SUBMITTING이면 `OrderCancelException("주문 접수를 처리하는 중입니다. 잠시 후 다시 시도해 주세요.")`.
- 60초 근거: 선점은 `place()` 호출 전이고 KIS 호출 간격 게이트는 그 안쪽이라(`KisHttpClient:150-175`), SUBMITTING 구간 = 게이트 대기(상한 20초, `:102`) + 읽기 타임아웃 7초 + 401 시 게이트 재통과·재시도(최악 ≈ 54초). Toss는 10초 × 최대 4회 + 백오프 ≈ 42초. 60초는 두 경로 정상 최악을 덮는다. 대신 배치 접수 루프와 겹친 일시정지 응답이 최대 1분 걸릴 수 있다 — 접수창(개장 22:00·마감 04:30 직후)에만 생기는 드문 경우로 수용. 게이트 통과 후 선점(브로커 어댑터에 송신 직전 콜백) 안은 trading 의미가 broker로 새어 기각. 잔류 판정 2분(3.5)과는 별개 값. [내 판단]
- 잔류 SUBMITTING(KIS, Toss 9분 초과)은 런북(3.9) 정정 전까지 전략·계좌 삭제와 탈퇴 cascade를 **계속 막는다**(증권사에 살아 있을 수 있는 주문을 고아로 만들지 않기 위해 — 기존 재시도 가능 취소 실패와 같은 규칙). 탈퇴는 EPR 재발행마다 재시도·알림이 반복된다(기존 동작과 같음). [내 판단]
- `ReorderService`: SUBMITTING 원본은 기존 "미체결(PLANNED·PLACED)만" 검증에서 자연히 거부된다(변경 없음, 문구 확인만).

### 3.7 재주문 IMMEDIATE도 같은 선점 패턴

`ReorderService.placeOrSave` IMMEDIATE(`:136-160`): 지금은 증권사 접수 후 `saveAll`. 변경 후 **SUBMITTING으로 먼저 저장**(id 확보 → `clientOrderId`) → 접수 → `markPlaced`/`markFailed`/유지(3.3). 저장 실패 시 HTTP를 보내지 않는다 → 기존 "[DB 불일치] 재주문 … 저장 실패" 경로가 사라진다. 응답 상태는 결과에 따라 PLACED/FAILED/SUBMITTING. (저장 후 id를 돌려받는 포트 메서드가 필요 — 형태는 구현자 재량.)

### 3.8 리포트·재개

- `TradingReporter`는 PLACED만 다룬다(변경 없음). 리포트 시점 사이클에 SUBMITTING이 남아 있으면 관리자 알림 `[접수 결과 불명 — 리포트 제외]` — 실제로 접수·체결됐다면 포지션이 어긋나므로 런북 정정 후 수동 체결 보정 대상.
- `TradingBatchResumer`의 `PLACING` 재개 경고 문구는 "SUBMITTING 잔류 주문은 재접수되지 않음"으로 갱신(동작 변경 없음).

### 3.9 운영 정정 런북 (`docs/agents/docker-infra.md`)

증권사 앱/조회로 해당 주문 존재 여부 확인 후:
- 접수됨: `UPDATE trading.orders SET status='PLACED', external_order_id='<번호>' WHERE id='<id>' AND status='SUBMITTING';`
- 미접수: `… SET status='FAILED' …` (같은 날 재실행이 leg를 다시 만든다)

## 4. 제약

- 전이는 기존 `lockOrConflict`(flush + `refresh(PESSIMISTIC_WRITE)`) 패턴. 증권사 HTTP는 트랜잭션 밖.
- `OrderStatus`는 `:shared` 공용 enum이고 root가 내부 API `contract.trading.OrderResponse`·`ReorderResponse`로 역직렬화한다 → **kista-api(root)를 kista-trading보다 먼저 또는 같은 커밋으로 함께 배포**. 커밋 메시지·배포 메모에 명시.
- 상태 전이 변경이므로 `src/test/resources/architecture/flows.yml` `lifecycles.order`(states·transitions, `ArchitectureMapTest`가 enum 상수 집합 일치를 강제)와 관련 흐름 `state`/`desc`(close-batch/place, manual-run/cancel-*, admin-orders/reorder-place) 갱신.
- 문서: `workflow.md`("PLANNED 상태 전이 경합", "병렬 접수 인터럽트 리스크", "미체결 주문 정리 · 재주문"), `toss-api.md`(clientOrderId), `docker-infra.md`(런북).
- kista-ui(별도 레포, 이번 범위 밖 — 후속 안내): `entities/order/model/status-badge.ts` 라벨·색상에 SUBMITTING("접수 중") 추가, 관리자 보정 폼은 SUBMITTING을 닫힌 상태로 취급(현 조건 `status !== 'PLANNED' && !== 'PLACED'`로 이미 그렇게 동작). UI 쪽 미지원 값 표시 방식은 구현 시 확인.
- 실계좌 주문이 필요한 검증(Toss 실제 멱등 재반환, KIS 4xx/`rt_cd` 분류의 실제 응답)은 하지 않고 "미검증(사유)"로 보고.
- 테스트: 단위(`TradingOrderExecutor` 선점·분류·대사, `OrderCancelService` 대기, `ReorderService` IMMEDIATE), persistence DB 테스트(SUBMITTING 전이·합계 쿼리), 변이 확인. 전체 스위트는 최종 1회.

## 5. 구현자 재량 (내 판단으로 정한 것 표시)

- [내 판단] 대기 60초·잔류 판정 2분·Toss 대사 상한 9분 값. 상수로 두고 설정화하지 않는다.
- [내 판단] 메서드 rename 대신 주석 갱신.
- [내 판단] 선점 시각은 `updated_at` 재사용(컬럼 추가 없음).
- 확정 거절 플래그의 이름·전달 방식, 저장 후 id 반환 포트 형태, 대사 로직의 클래스 위치(executor 내부 vs 별도 컴포넌트).
