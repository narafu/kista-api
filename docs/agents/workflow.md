## 스케쥴러 실행 흐름
- 스케쥴러 기동: `TradingCloseScheduler` 화~토 04:30 KST (DST 장마감 30분 전, 비DST는 orderAt 05:30까지 대기) → `StrategyPort.findAllActive()`로 ACTIVE 사이클 목록 조회
- context 리스트 빌드: 사이클별 계좌·사용자 조회 (실패 시 해당 사이클 skip + `notifyError`) → `ExecuteTradingUseCase.executeBatch(contexts)` 1회 호출
- `TradingService.executeBatch()`: 고유 ticker 수집 → 가격 1회 일괄 조회 → leg-aware 슬롯별 후보 수집 → 신규 BUY 가격 cap·correction 사전 계산 → 계좌별 예산 배정 → 사이클별 접수·리포트 병렬 실행 (`TradingParallelRunner`, 계좌 groupKey별 동시 상한 `app.trading.parallel-per-account`=2, 각 실패 격리 catch + `notifyError`, 결과는 제출 순서 보존). 상한 0 이하이면 호출 스레드 순차 인라인 실행 — 단위 테스트는 `new TradingParallelRunner(0)`으로 결정성 확보
- **leg-aware 주문 생성**: 신규 전략 주문은 내부 `orders.order_leg`로 주문 leg를 식별한다. concrete leg는 `timing + direction + orderLeg` 슬롯을 점유하고, 기존 `UNKNOWN` leg 행은 과거 데이터 호환을 위해 `timing + direction` coarse 슬롯으로 처리한다. 기존 주문이 있더라도 점유되지 않은 concrete leg만 후보로 남겨 `PLANNED` 저장한다. **`AT_CLOSE` 슬롯(INFINITE BUY, PRIVACY BUY/SELL, VR bootstrap 등)은 마감 스케쥴러가 전담 생성**하며, 개장 스케쥴러는 `AT_OPEN` 슬롯만 생성·선접수한다 — AT_CLOSE 캡이 개장 시점 가격으로 고정돼 마감 접수까지 재평가되지 않는 stale-cap 문제를 막기 위한 설계다.
- 계좌별 예산 배정: `TradingOrderBudgetAllocator`가 BUY와 SELL을 독립적으로 처리한다. BUY와 SELL 모두 계좌별 `CycleOrderStrategy.allocationPriority()` 기준 `VR → INFINITE → PRIVACY` 우선순위를 따른다. BUY는 같은 전략 타입에서 총 매수금액이 작은 사이클 우선, SELL은 같은 전략 타입에서 필요 매도수량이 작은 사이클 우선이며 동률이면 strategyId, cycleId 오름차순으로 결정한다. 한 사이클의 BUY 주문은 all-or-nothing으로 처리하며, 기존 당일 PLANNED BUY 금액도 예산에서 차감한다. SELL은 계좌·종목별 판매가능수량과 기존 PLANNED/PLACED 예약분을 기준으로 별도 배정한다. 승인된 방향만 남기되 후보 내부의 원래 주문 순서를 보존한다. 계좌별 라이브 잔고(`getLiveBalance`)·종목별 판매가능수량(`getSellableQuantity`)은 `fetchLiveQuotes`가 계좌 단위로 병렬 선(先) 조회해 `LiveQuotes` Map으로 만든 뒤, 계좌 내 우선순위 예산 차감 계산은 순차로 수행한다. 계좌별 조회 실패는 `AccountQuote.failure`로 캡슐화되어 배정 시점에 원본 예외로 rethrow → `runSafely` 격리 유지.
- 마감 경로: 잔고 조회 → 현재가(배치 캐시 or 단건 fallback) → 전략 계산·BUY cap 사전 계산 → 누락된 `AT_CLOSE` 주문만 예산 배정 후 `orders`에 PLANNED 저장 → `DstInfo.waitUntilOrderTime()` 대기 (cron 04:30 발화 기준 DST≈0분, 비DST=60분 — orderAt은 DST=04:30/비DST=05:30) → 접수 대상 ticker 현재가를 다시 일괄 재조회(`reloadPlacementPrices`, 조회 실패 시 배치 시작 가격으로 폴백) → `BuyOrderPriceCapper`로 BUY cap 재보정 → AT_CLOSE 주문 접수 (PLACED 기록) → 체결 리포트. 신규 BUY·SELL이 모두 거절되거나 신규 주문 저장이 실패하고 기존 주문도 없는 사이클은 접수·리포트 대상에서 제외하며, 기존 PLANNED/PLACED 주문이 있으면 후속 흐름을 유지한다.
- 개장 경로: leg-aware 후보·예산 배정을 `AT_OPEN` 슬롯에 대해서만 수행해 누락분을 저장한다(`AT_CLOSE`는 생성 대상에서 제외). `DstInfo.waitUntilMarketOpen()` 대기 후 접수 대상 ticker 현재가를 재조회(`reloadPlacementPrices`, 마감 경로와 동일 함수)하고, `TradingOrderExecutor.placeAtOpenOrders()`가 이 가격으로 `BuyOrderPriceCapper` BUY cap 보정을 적용한 뒤 `AT_OPEN` PLANNED 주문만 선접수한다. 마감 경로에서는 선접수된 주문도 포함해 중복 없이 후속 접수·리포트한다.
- 재계산 skip: correction까지 포함된 complete INFINITE concrete leg 조합 또는 direction-aware legacy `UNKNOWN` 양방향 점유처럼 안전한 경우에만 전략 주문 계산을 생략한다. 리버스 `AT_CLOSE`는 `REVERSE_INFINITE_LOC_BUY` BUY 슬롯과 `REVERSE_INFINITE_LOC_SELL` SELL 슬롯이 모두 있어야 complete로 본다. partial concrete leg는 항상 계산해 누락 leg를 복구한다. 개장 스케쥴러는 `AT_OPEN`만 생성 대상이라 이 스킵 판정도 `AT_OPEN` 슬롯 완전성만 본다. VR/PRIVACY concrete compute skip은 ladder 길이가 variable이라 비활성화한다.
- `BuyOrderPriceCapper.buildCappedBuyOrders`(INFINITE)는 재진입(같은 close 배치 내 접수 직전 재조회 가격이 최초 계산 시점보다 추가로 하락해 캡이 다시 트리거되는 경우) 시 입력에 이미 `INFINITE_CORRECTION_*` leg가 섞여 있으면 base 주문(평단가/기준가)만 추출해 재산정한다 — correction leg를 base로 오인해 잘못 계산하는 것을 방지.
- 계좌별 브로커 토큰: KIS는 `broker_tokens` 테이블에 account_id(PK) 기준 독립 관리 (`KisTokenEntity`), Toss 계좌·관리자 토큰은 Redis canonical hash에 공유 (`TossDistributedTokenCoordinator` + `TossRedisTokenStore`)
- 실행 결과: `UserNotificationPort.notifyTradingReport(user, account, report)` — 사용자봇 미설정 시 생략
- 오류 시: `NotifyPort.notifyError(e)`로 관리자 알림 + 다음 사이클 계속 실행. 계좌별 예산 배정, 사이클별 PLANNED 저장, 잔고 부족 사용자 알림 실패는 각각 격리되어 다른 계좌·사이클 처리를 막지 않는다.
- `waitFor()` 대기 중 `InterruptedException`(배포·재시작으로 인한 강제 종료) 발생 시 `notifyPort.notifyError()`로 관리자 알림 후 rethrow — PLANNED 주문 접수 미실행 가능성 알림
- **병렬 접수 인터럽트 리스크(운영 주의)**: 접수 병렬화로 배포·재시작 인터럽트 시 torn-order 범위가 확대된다. Virtual Thread는 인터럽트 시 진행 중 소켓을 강제 종료(JDK21 `Socket` 계약)하므로, 접수 HTTP 응답 대기 중 인터럽트되면 `SocketException`이 `TradingOrderExecutor.placeEach`의 `catch(Exception)`에서 브로커 거절과 구분 없이 `markFailed`로 처리된다 — 브로커는 이미 접수·체결했을 수 있어 DB=FAILED / 브로커=체결 불일치 가능. 순차 시 최대 1건이던 이 위험이 병렬 시 동시 진행 중이던 `계좌수 × parallel-per-account(2)`건으로 늘어난다. 저빈도(배포 시점 접수창 겹칠 때)이나, 배포 타이밍을 접수창(개장 22:30·마감 04:30 KST 직후) 밖으로 두거나 후속으로 `placeEach`에서 인터럽트 기인 실패를 "수동 확인 필요"로 격상하는 완화가 권장된다.
- `TradingService`에 INFO 로그 있음 — 사이클별 단계(개장 확인, 잔고, 주문, 체결)마다 찍힘
- `KbLandHousingBenchmarkScheduler`: 매월 10일·20일 07:00 KST `kbland-housing-benchmark` 분산 락으로 실행 — KB Land 최근 1년치 아파트 5분위 매매평균가격을 자연키(source+metric+region+baseMonth) 기준 upsert

### DstInfo.MarketSession (수동 실행 시간대 판단)
- `DIRECT`: 프리마켓+정규장 전 구간 — 주문 가능 (DST: 17:00~05:00 / 비DST: 18:00~06:00 KST)
- `BLOCKED`: 장마감~프리마켓 전 — 주문 불가 (DST: 05:00~17:00 / 비DST: 06:00~18:00 KST)
- `ManualTradingService.execute()` 수동 실행 진입 시 BLOCKED이면 `IllegalStateException` → 컨트롤러 503; DIRECT(개장 후)이면 AT_OPEN PLANNED 주문(INFINITE는 매도 선접수, VR은 매수·매도 사다리)을 `TradingOrderExecutor.placeAtOpenOrders()`로 즉시 접수한다 — 개장 스케쥴러와 동일하게 BUY cap 보정(`BuyOrderPriceCapper`)을 거친 뒤 접수되며, 반환은 `findPlannedOrPlacedByCycleAndDate`. SELL 가능수량 검증은 같은 계좌·거래일·ticker의 기존 PLANNED/PLACED 예약 수량과 신규 SELL 합계를 사용한다.
- `GET /api/market/session`: UI 수동 실행 버튼 활성화 판단용, `{ session: "DIRECT"|"BLOCKED", isDst: boolean }` 반환
- kista-ui `NextOrderPreviewCard`: BLOCKED이거나 오늘이 휴장일이면 "지금 실행" 버튼 disabled + title 툴팁

### BuyOrderPriceCapper 보정 주문

INFINITE/PRIVACY/VR 세 전략 모두 대상이며, 각 전략마다 AT_CLOSE 스코프(`capIfNeeded`/`capPrivacyIfNeeded`/`capVrIfNeeded`)와 AT_OPEN 스코프(`capIfNeededAtOpen`/`capPrivacyIfNeededAtOpen`/`capVrIfNeededAtOpen`) 메서드가 쌍으로 존재한다 — AT_OPEN 스코프는 `findAtOpenPlannedByCycleAndDate`로 AT_OPEN PLANNED만 조회해 동일 사이클의 미도래 AT_CLOSE PLANNED를 건드리지 않는다. AT_CLOSE 접수(`TradingOrderExecutor.placeOrders()`)와 AT_OPEN 접수(`placeAtOpenOrders()`) 양쪽에서 `CycleOrderStrategy.priceCapMode()`로 분기해 호출한다.

#### INFINITE 전략 (전후반 공통)
- 신규 후보는 `prepareForAllocation`에서 cap 후 base BUY 재산정과 correction BUY 생성을 먼저 수행하며, 이 최종 BUY 총액이 예산 배정 입력이 된다. 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.10` 초과 시 가격 캡 후 수량 재산정 (`capIfNeeded`) — 재산정·보정 로직 자체는 `InfiniteStrategy.buildCappedBuyOrders()`에 위임 (아래 `computeEarlyBuys`/`computeLateBuys`/`CORRECTION_ORDER_COUNT`는 InfiniteStrategy 내부 심볼)
- 전반(buyOrders 2건): `computeEarlyBuys` — cappedAvg/cappedRef 기준 buy①② 재산정, 동가 시 병합
- 후반(buyOrders 1건): `computeLateBuys` — cappedPrice 기준 단일 LOC 수량 재산정
- **보정 주문 (전후반 공통)**: base buy 재산정 후 `CORRECTION_ORDER_COUNT`(=3)회 LOC 1주 추가
  - 가격 = `K / (누적수량 + 1)` (HALF_UP, scale=2) — 매 회 직전까지 추가된 주문 수량 합산 기준
  - 누적수량이 0이면 해당 회차 skip
- 재산정 결과가 모두 비어있으면 BUY 주문 전체 제외 (log.warn)
- INFINITE BUY는 항상 AT_CLOSE라 `capIfNeededAtOpen`은 실질 no-op — `priceCapMode()` 분기 대칭성 유지 목적으로만 존재

#### PRIVACY 전략
- 신규 후보는 allocator 전에 cap 초과 BUY 가격만 교체한 금액으로 예산을 검증하며, 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.10` 초과 시 (`capPrivacyIfNeeded`)
- **수량 재산정 없음** — cap 초과 BUY 주문가만 `currentPrice × 1.10`으로 교체, 수량은 FIDA 원본 유지
- `TradingOrderExecutor.placeOrders()`: `position == null && currentPrice != null` 분기 → `capPrivacyIfNeeded` 호출
- `TradingService`: PRIVACY도 `startPrice = price`로 `CycleState`에 전달 (이전에는 `null` → 캡 미적용 버그)
- PRIVACY BUY도 항상 AT_CLOSE라 `capPrivacyIfNeededAtOpen`은 실질 no-op (대칭성 유지 목적)

#### VR 전략
- bootstrap 주문(LOC+AT_CLOSE, `referencePrice × 1.10`)은 사다리 공식과 무관한 별도 산정식이라 보정 대상에서 제외한다 — BUY 중 하나라도 `OrderType.LOC`이면 이번 배치 전체를 bootstrap으로 판별해 skip(`isVrBootstrapShaped`)
- 사다리(LIMIT+AT_OPEN) BUY만 보정 대상: cap 초과 시 기존 buyOrders 인자 없이 `VrPosition`+cap만으로 `VrStrategy.buildCappedBuyOrders()`가 사다리 전체를 자기완결적으로 재생성 — poolLimit·pool 한도 내로 자연 재수렴
- AT_CLOSE 스코프(`capVrIfNeeded`)는 사이클+거래일 전체 PLANNED BUY를, AT_OPEN 스코프(`capVrIfNeededAtOpen`)는 `findAtOpenPlannedByCycleAndDate`로 사다리 BUY만 조회 — bootstrap이 같은 사이클에 공존해도 스코프 밖이라 자연히 제외됨

### TradingService 기록 테이블 구분
- `orders`: 주문 단위 이벤트 로그 — 실행당 N건 (mainOrders + corrections 모두 저장, order_type/direction/quantity/price/status 포함)
  - `order_leg`: 내부 leg 식별자. 신규 전략 주문은 `INFINITE_EARLY_AVG_BUY`, `VR_BUY_01`, `PRIVACY_SELL_01` 같은 concrete 값을 저장하고, legacy 행은 `UNKNOWN`으로 backfill된다. 브로커 API와 외부 응답 DTO에는 전달하지 않는다.
  - 증권사 접수 실패 → `OrderPort.markFailed(orderId)`로 FAILED 기록 (`TradingOrderExecutor`)
  - 체결 리포트 집계 시 체결 내역 없는 PLACED 주문(미체결) → `OrderPort.markCancelled(orderId)`로 CANCELLED 기록 (`TradingReporter`)
- `cycle_position`: 사이클 단위 포지션 스냅샷 — 실행당 1건 append (`CyclePositionPort.save()`, dedup/UNIQUE 제약 없음). 필드: usd_deposit/avg_price/holdings/closing_price
- `trade_histories`·`portfolio_snapshots` 테이블은 존재하지 않음 — 참조 금지
