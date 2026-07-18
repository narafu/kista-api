## 스케쥴러 실행 흐름
- 스케쥴러 기동: `TradingCloseScheduler` 화~토 04:30 KST (DST 장마감 30분 전, 비DST는 orderAt 05:30까지 대기) → `StrategyPort.findAllActive()`로 ACTIVE 사이클 목록 조회
- context 리스트 빌드: 사이클별 계좌·사용자 조회 (실패 시 해당 사이클 skip + `notifyError`) → `ExecuteTradingUseCase.executeBatch(contexts)` 1회 호출
- `TradingService.executeBatch()`: 고유 ticker 수집 → 가격 1회 일괄 조회 → leg-aware 슬롯별 후보 수집 → 신규 BUY 가격 cap·correction 사전 계산 → 계좌별 예산 배정 → 사이클별 순차 실행 (각 실패 격리 catch + `notifyError`)
- **leg-aware 주문 생성**: 신규 전략 주문은 내부 `orders.order_leg`로 주문 leg를 식별한다. concrete leg는 `timing + direction + orderLeg` 슬롯을 점유하고, 기존 `UNKNOWN` leg 행은 과거 데이터 호환을 위해 `timing + direction` coarse 슬롯으로 처리한다. 기존 주문이 있더라도 점유되지 않은 concrete leg만 후보로 남겨 `PLANNED` 저장한다. 마감 복구 경로는 **`AT_CLOSE` 슬롯만** 생성하며, `AT_OPEN` 주문은 개장 스케쥴러에서만 선접수한다.
- 계좌별 예산 배정: `TradingOrderBudgetAllocator`가 BUY와 SELL을 독립적으로 처리한다. BUY와 SELL 모두 계좌별 `CycleOrderStrategy.allocationPriority()` 기준 `VR → INFINITE → PRIVACY` 우선순위를 따른다. BUY는 같은 전략 타입에서 총 매수금액이 작은 사이클 우선, SELL은 같은 전략 타입에서 필요 매도수량이 작은 사이클 우선이며 동률이면 strategyId, cycleId 오름차순으로 결정한다. 한 사이클의 BUY 주문은 all-or-nothing으로 처리하며, 기존 당일 PLANNED BUY 금액도 예산에서 차감한다. SELL은 계좌·종목별 판매가능수량과 기존 PLANNED/PLACED 예약분을 기준으로 별도 배정한다. 승인된 방향만 남기되 후보 내부의 원래 주문 순서를 보존한다.
- 마감 경로: 잔고 조회 → 현재가(배치 캐시 or 단건 fallback) → 전략 계산·BUY cap 사전 계산 → 누락된 `AT_CLOSE` 주문만 예산 배정 후 `orders`에 PLANNED 저장 → `DstInfo.waitUntilOrderTime()` 대기 (cron 04:30 발화 기준 DST≈0분, 비DST=60분 — orderAt은 DST=04:30/비DST=05:30) → AT_CLOSE 주문 접수 (PLACED 기록) → 체결 리포트. 신규 BUY·SELL이 모두 거절되거나 신규 주문 저장이 실패하고 기존 주문도 없는 사이클은 접수·리포트 대상에서 제외하며, 기존 PLANNED/PLACED 주문이 있으면 후속 흐름을 유지한다.
- 개장 경로: 동일한 leg-aware 후보·예산 배정을 수행하되 누락된 `AT_OPEN` 주문도 저장하고, 개장 시점에는 `AT_OPEN` PLANNED 주문만 선접수한다. 마감 경로에서는 선접수된 주문도 포함해 중복 없이 후속 접수·리포트한다.
- 재계산 skip: correction까지 포함된 complete INFINITE concrete leg 조합 또는 direction-aware legacy `UNKNOWN` 양방향 점유처럼 안전한 경우에만 전략 주문 계산을 생략한다. 리버스 `AT_CLOSE`는 `REVERSE_INFINITE_LOC_BUY` BUY 슬롯과 `REVERSE_INFINITE_LOC_SELL` SELL 슬롯이 모두 있어야 complete로 본다. partial concrete leg는 항상 계산해 누락 leg를 복구한다. 개장 스케쥴러처럼 `AT_OPEN`까지 생성 대상이면 해당 timing의 SELL leg 누락 가능성도 보수적으로 본다. VR/PRIVACY concrete compute skip은 ladder 길이가 variable이라 비활성화한다.
- 계좌별 브로커 토큰: `broker_tokens` 테이블에 account_id(PK) 기준 독립 관리 (`KisTokenEntity`)
- 실행 결과: `UserNotificationPort.notifyTradingReport(user, account, report)` — 사용자봇 미설정 시 생략
- 오류 시: `NotifyPort.notifyError(e)`로 관리자 알림 + 다음 사이클 계속 실행. 계좌별 예산 배정, 사이클별 PLANNED 저장, 잔고 부족 사용자 알림 실패는 각각 격리되어 다른 계좌·사이클 처리를 막지 않는다.
- `waitFor()` 대기 중 `InterruptedException`(배포·재시작으로 인한 강제 종료) 발생 시 `notifyPort.notifyError()`로 관리자 알림 후 rethrow — PLANNED 주문 접수 미실행 가능성 알림
- `TradingService`에 INFO 로그 있음 — 사이클별 단계(개장 확인, 잔고, 주문, 체결)마다 찍힘
- `KbLandHousingBenchmarkScheduler`: 매월 10일·20일 09:00 KST `kbland-housing-benchmark` 분산 락으로 실행 — KB Land 최근 1년치 아파트 5분위 매매평균가격을 자연키(source+metric+region+baseMonth) 기준 upsert

### DstInfo.MarketSession (수동 실행 시간대 판단)
- `DIRECT`: 프리마켓+정규장 전 구간 — 주문 가능 (DST: 17:00~05:00 / 비DST: 18:00~06:00 KST)
- `BLOCKED`: 장마감~프리마켓 전 — 주문 불가 (DST: 05:00~17:00 / 비DST: 06:00~18:00 KST)
- `ManualTradingService.execute()` 수동 실행 진입 시 BLOCKED이면 `IllegalStateException` → 컨트롤러 503; DIRECT(개장 후)이면 INFINITE AT_OPEN 매도 주문 즉시 `placeGiven` 접수 (`TradingOrderExecutor`), 반환은 `findPlannedOrPlacedByCycleAndDate`. SELL 가능수량 검증은 같은 계좌·거래일·ticker의 기존 PLANNED/PLACED 예약 수량과 신규 SELL 합계를 사용한다.
- `GET /api/market/session`: UI 수동 실행 버튼 활성화 판단용, `{ session: "DIRECT"|"BLOCKED", isDst: boolean }` 반환
- kista-ui `NextOrderPreviewCard`: BLOCKED이거나 오늘이 휴장일이면 "지금 실행" 버튼 disabled + title 툴팁

### BuyOrderPriceCapper 보정 주문

#### INFINITE 전략 (전후반 공통)
- 신규 후보는 `prepareForAllocation`에서 cap 후 base BUY 재산정과 correction BUY 생성을 먼저 수행하며, 이 최종 BUY 총액이 예산 배정 입력이 된다. 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.10` 초과 시 가격 캡 후 수량 재산정 (`capIfNeeded`) — 재산정·보정 로직 자체는 `InfiniteStrategy.buildCappedBuyOrders()`에 위임 (아래 `computeEarlyBuys`/`computeLateBuys`/`CORRECTION_ORDER_COUNT`는 InfiniteStrategy 내부 심볼)
- 전반(buyOrders 2건): `computeEarlyBuys` — cappedAvg/cappedRef 기준 buy①② 재산정, 동가 시 병합
- 후반(buyOrders 1건): `computeLateBuys` — cappedPrice 기준 단일 LOC 수량 재산정
- **보정 주문 (전후반 공통)**: base buy 재산정 후 `CORRECTION_ORDER_COUNT`(=3)회 LOC 1주 추가
  - 가격 = `K / (누적수량 + 1)` (HALF_UP, scale=2) — 매 회 직전까지 추가된 주문 수량 합산 기준
  - 누적수량이 0이면 해당 회차 skip
- 재산정 결과가 모두 비어있으면 BUY 주문 전체 제외 (log.warn)

#### PRIVACY 전략
- 신규 후보는 allocator 전에 cap 초과 BUY 가격만 교체한 금액으로 예산을 검증하며, 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.10` 초과 시 (`capPrivacyIfNeeded`)
- **수량 재산정 없음** — cap 초과 BUY 주문가만 `currentPrice × 1.10`으로 교체, 수량은 FIDA 원본 유지
- `TradingOrderExecutor.placeOrders()`: `position == null && currentPrice != null` 분기 → `capPrivacyIfNeeded` 호출
- `TradingService`: PRIVACY도 `startPrice = price`로 `CycleState`에 전달 (이전에는 `null` → 캡 미적용 버그)

### TradingService 기록 테이블 구분
- `orders`: 주문 단위 이벤트 로그 — 실행당 N건 (mainOrders + corrections 모두 저장, order_type/direction/quantity/price/status 포함)
  - `order_leg`: 내부 leg 식별자. 신규 전략 주문은 `INFINITE_EARLY_AVG_BUY`, `VR_BUY_01`, `PRIVACY_SELL_01` 같은 concrete 값을 저장하고, legacy 행은 `UNKNOWN`으로 backfill된다. 브로커 API와 외부 응답 DTO에는 전달하지 않는다.
  - 증권사 접수 실패 → `OrderPort.markFailed(orderId)`로 FAILED 기록 (`TradingOrderExecutor`)
  - 체결 리포트 집계 시 체결 내역 없는 PLACED 주문(미체결) → `OrderPort.markCancelled(orderId)`로 CANCELLED 기록 (`TradingReporter`)
- `cycle_position`: 사이클 단위 포지션 스냅샷 — 실행당 1건 append (`CyclePositionPort.save()`, dedup/UNIQUE 제약 없음). 필드: usd_deposit/avg_price/holdings/closing_price
- `trade_histories`·`portfolio_snapshots` 테이블은 존재하지 않음 — 참조 금지
