## 스케쥴러 실행 흐름
- 매매 스케쥴러(`TradingOpenScheduler`/`TradingCloseScheduler`)는 `:trading-core`의 `kista-trading` 프로세스에서 실행된다(`kista-scheduler`엔 매매 빈이 없음 — 시간표 → `scheduler-time-table.md`)
- 스케쥴러 기동: `TradingCloseScheduler` 화~토 04:30 KST (DST 장마감 30분 전, 비DST는 orderAt 05:30까지 대기) → `StrategyPort.findAllActive()`로 ACTIVE 사이클 목록 조회
- context 리스트 빌드: 사이클별 계좌·사용자 조회 (실패 시 해당 사이클 skip + `TradingErrorReportPort.reportError`) → `TradingExecutionUseCase.executeBatch(contexts)` 1회 호출(`TradingExecutionFacade` → `TradingService`)
- `TradingService.executeBatch()`: 고유 ticker 수집 → 가격 1회 일괄 조회 → leg-aware 슬롯별 후보 수집 → 신규 BUY 가격 cap·correction 사전 계산 → 계좌별 예산 배정 → 사이클별 접수·리포트 병렬 실행 (`TradingParallelRunner`, 계좌 groupKey별 동시 상한 `app.trading.parallel-per-account`=2, 각 실패 격리 catch + 오류 알림, 결과는 제출 순서 보존). 상한 0 이하이면 호출 스레드 순차 인라인 실행 — 단위 테스트는 `new TradingParallelRunner(0)`으로 결정성 확보
- **leg-aware 주문 생성**: 신규 전략 주문은 내부 `orders.order_leg`로 주문 leg를 식별한다. concrete leg는 `timing + direction + orderLeg` 슬롯을 점유하고, 기존 `UNKNOWN` leg 행은 과거 데이터 호환을 위해 `timing + direction` coarse 슬롯으로 처리한다. 기존 주문이 있더라도 점유되지 않은 concrete leg만 후보로 남겨 `PLANNED` 저장한다. **`AT_CLOSE` 슬롯(INFINITE BUY, PRIVACY BUY/SELL, VR bootstrap 등)은 마감 스케쥴러가 전담 생성**하며, 개장 스케쥴러는 `AT_OPEN` 슬롯만 생성·선접수한다 — AT_CLOSE 캡이 개장 시점 가격으로 고정돼 마감 접수까지 재평가되지 않는 stale-cap 문제를 막기 위한 설계다.
- 계좌별 예산 배정: `TradingOrderBudgetAllocator`가 BUY와 SELL을 독립적으로 처리한다. BUY와 SELL 모두 계좌별 `CycleOrderStrategy.allocationPriority()` 기준 `VR → INFINITE → PRIVACY` 우선순위를 따른다. BUY는 같은 전략 타입에서 총 매수금액이 작은 사이클 우선, SELL은 같은 전략 타입에서 필요 매도수량이 작은 사이클 우선이며 동률이면 strategyId, cycleId 오름차순으로 결정한다. 한 사이클의 BUY 주문은 all-or-nothing으로 처리하며, 기존 당일 PLANNED BUY 금액도 예산에서 차감한다. SELL은 계좌·종목별 판매가능수량과 기존 PLANNED/PLACED 예약분을 기준으로 별도 배정한다. 승인된 방향만 남기되 후보 내부의 원래 주문 순서를 보존한다. `TradingOrderBudgetAllocator`는 단일계좌 전용 — `allocate(candidates, tradeDate)` 2-인자 진입점 하나가 계좌별 라이브 잔고(`getLiveBalance`)·종목별 판매가능수량(`getSellableQuantity`) 조회와 예산 차감 계산을 한 번에 수행한다(내부 재그룹 없음). 계좌 간 병렬화는 한 단계 위 `TradingCandidatePlanner.saveAllocatedOrders()`가 계좌별 "조회+배정" 전체를 `TradingParallelRunner` 태스크 하나로 묶어 담당하며(`batchGuard.runSafely`로 격리), 계좌 내부(한 계좌의 우선순위 예산 차감)는 여전히 순차 수행이다.
- 마감 경로: 잔고 조회 → 현재가(배치 캐시 or 단건 fallback) → 전략 계산·BUY cap 사전 계산 → 누락된 `AT_CLOSE` 주문만 예산 배정 후 `orders`에 PLANNED 저장 → `DstInfo.waitUntilOrderTime()` 대기 (cron 04:30 발화 기준 DST≈0분, 비DST=60분 — orderAt은 DST=04:30/비DST=05:30) → 접수 대상 ticker 현재가를 다시 일괄 재조회(`reloadPlacementPrices`, 조회 실패 시 배치 시작 가격으로 폴백) → `BuyOrderPriceCapper`로 BUY cap 재보정 → AT_CLOSE 주문 접수 (PLACED 기록) → 체결 리포트. 신규 BUY·SELL이 모두 거절되거나 신규 주문 저장이 실패하고 기존 주문도 없는 사이클은 접수·리포트 대상에서 제외하며, 기존 PLANNED/PLACED 주문이 있으면 후속 흐름을 유지한다.
- 개장 경로: leg-aware 후보·예산 배정을 `AT_OPEN` 슬롯에 대해서만 수행해 누락분을 저장한다(`AT_CLOSE`는 생성 대상에서 제외). `DstInfo.waitUntilMarketOpen()` 대기 후 접수 대상 ticker 현재가를 재조회(`reloadPlacementPrices`, 마감 경로와 동일 함수)하고, `TradingOrderExecutor.placeAtOpenOrders()`가 이 가격으로 `BuyOrderPriceCapper` BUY cap 보정을 적용한 뒤 `AT_OPEN` PLANNED 주문만 선접수한다. 마감 경로에서는 선접수된 주문도 포함해 중복 없이 후속 접수·리포트한다.
- 재계산 skip: correction까지 포함된 complete INFINITE concrete leg 조합 또는 direction-aware legacy `UNKNOWN` 양방향 점유처럼 안전한 경우에만 전략 주문 계산을 생략한다. 리버스 `AT_CLOSE`는 `REVERSE_INFINITE_LOC_BUY` BUY 슬롯과 `REVERSE_INFINITE_LOC_SELL` SELL 슬롯이 모두 있어야 complete로 본다. partial concrete leg는 항상 계산해 누락 leg를 복구한다. 개장 스케쥴러는 `AT_OPEN`만 생성 대상이라 이 스킵 판정도 `AT_OPEN` 슬롯 완전성만 본다. VR/PRIVACY concrete compute skip은 ladder 길이가 variable이라 비활성화한다.
- `BuyOrderPriceCapper.buildCappedBuyOrders`(INFINITE)는 재진입(같은 close 배치 내 접수 직전 재조회 가격이 최초 계산 시점보다 추가로 하락해 캡이 다시 트리거되는 경우) 시 입력에 이미 `INFINITE_CORRECTION_*` leg가 섞여 있으면 base 주문(평단가/기준가)만 추출해 재산정한다 — correction leg를 base로 오인해 잘못 계산하는 것을 방지.
- 계좌별 브로커 토큰: KIS는 `broker_tokens` 테이블에 account_id(PK) 기준 독립 관리 (`KisTokenEntity`), Toss 계좌·관리자 토큰은 Redis canonical hash에 공유 (`TossDistributedTokenCoordinator` + `TossRedisTokenStore`)
- 실행 결과: `TradingReporter`가 `TradingReportReadyEvent`를 발행 → `tradingnotify`의 `TradingReportNotifier`가 알림 — 사용자봇 미설정 시 생략
- 오류 시: `TradingErrorEvent` 발행(`TradingBatchGuard.notifyErrorSafely`/`TradingErrorReportPort`)으로 관리자·사용자 알림 + 다음 사이클 계속 실행. 계좌별 예산 배정, 사이클별 PLANNED 저장, 잔고 부족 사용자 알림 실패는 각각 격리되어 다른 계좌·사이클 처리를 막지 않는다.
- **재기동 종료·재개** (spec `docs/superpowers/specs/2026-10-02-trading-batch-resume-design.md`):
  - 종료 처리: 배치는 `TradingBatchRunState`에 스레드와 임계구역(마감 `placeAll`·리포트, 개장 계획~AT_OPEN 접수)을 등록한다. 종료 시 `TradingBatchShutdownCoordinator`(SmartLifecycle, 웹 graceful보다 먼저 stop)가 임계구역 완료를 최대 150s 기다린 뒤 대기 구간만 인터럽트한다. 이때 `waitFor`는 사용자 "미접수" 알림(`BatchInterruptedEvent`)을 억제하고 관리자 "[재기동] … 재개 예정"만 보낸다. 종료 요청이 아닌 인터럽트는 기존대로 "[스케쥴러 인터럽트]" 관리자 알림과 사용자 알림을 보낸다.
    - 배경: Boot 기본 스케쥴러(VT `SimpleAsyncTaskScheduler`, `await-termination` 미설정)는 종료 시 실행 중 배치를 인터럽트하지 않는다. 코디네이터가 없으면 배치는 JVM 종료와 함께 조용히 사라진다.
  - 체크포인트: 단계는 `trading.trading_batch_run`(job·KST 거래일)에 남는다. 마감은 `STARTED → PLANNED → PLACING → PLACED → DONE`, 개장은 `STARTED → PLACING → DONE`이다. 조기 반환도 `DONE`으로 기록하고, 시작 시 `STARTED`가 같은 날짜 수동 실행의 `DONE`을 덮어쓴다. 종료 요청 이후에는 배치 시작·임계구역 신규 진입이 거부된다(`requestStop`과 같은 락에서 판정).
  - 리포트 마커: 전략별 리포트 완료는 `trading.trading_batch_report`에 남는다. `CyclePositionPersistor`가 포지션 저장 직후 기록하며, 키가 전략인 이유는 rotation 후에도 유효해야 하기 때문이다.
  - 기동 시 재개: `TradingBatchResumer`(ApplicationReadyEvent)가 처리한다.
    - 마감 배치(화~토 04:30~22:30): 행 없음·`PLANNED`·`PLACING`이고 장마감 10분 전 이전이면 전체 재실행한다(`PLACING`은 이중 접수 경고). `PLACED`면 리포트만 재개한다(`resumeCloseReport` — 대상은 당일 주문이 있고 마커가 없는 전략, 사전 잔고는 최신 `cycle_position`, 대상 주문은 DB PLACED). 접수 마감을 지났으면 관리자 알림만 보낸다.
    - 개장 배치(월~금 22:30~ / 화~토 ~04:30): 행 없음·`PLACING`이면 재실행한다(`DstInfo.forOpenBatch` — 자정 이후 재개도 지난 개장을 기다리지 않음).
    - 락: 재개는 `SchedulerLockService.takeOver`로 이전 프로세스 락을 인수한다. 자기 프로세스 락(기동 직후 cron 선발화)은 인수하지 않는다.
  - 전제: **`kista-trading` 단일 인스턴스 + 이전 컨테이너 종료 후 기동(겹침 없음)**.
  - 남은 위험: SIGKILL·OOM·임계구역 150s 초과 시에는 접수 도중 torn-order(브로커 접수 / DB PLANNED·FAILED)가 남을 수 있다. 이 경우 `PLACING` 경고를 보고 수동 확인한다.
- **병렬 접수 인터럽트 리스크(운영 주의)**: 접수 병렬화로 배포·재시작 인터럽트 시 torn-order 범위가 확대된다. Virtual Thread는 인터럽트 시 진행 중 소켓을 강제 종료(JDK21 `Socket` 계약)하므로, 접수 HTTP 응답 대기 중 인터럽트되면 `SocketException`이 `TradingOrderExecutor.placeEach`의 `catch(Exception)`에서 브로커 거절과 구분 없이 `markFailed`로 처리된다 — 브로커는 이미 접수·체결했을 수 있어 DB=FAILED / 브로커=체결 불일치 가능. 순차 시 최대 1건이던 이 위험이 병렬 시 동시 진행 중이던 `계좌수 × parallel-per-account(2)`건으로 늘어난다. 저빈도(배포 시점 접수창 겹칠 때)이나, 배포 타이밍을 접수창(개장 22:30·마감 04:30 KST 직후) 밖으로 두거나 후속으로 `placeEach`에서 인터럽트 기인 실패를 "수동 확인 필요"로 격상하는 완화가 권장된다. graceful 재기동은 접수 임계구역 완료를 기다리므로(위 "재기동 종료·재개") 이 위험은 SIGKILL·OOM·150s 초과 시로 한정된다.
- `TradingService`에 INFO 로그 있음 — 사이클별 단계(개장 확인, 잔고, 주문, 체결)마다 찍힘
- `KbLandHousingBenchmarkScheduler`: 매주 토요일 08:00 KST `kbland-housing-benchmark` 분산 락으로 실행 — KB Land 최근 1년치 아파트 5분위 매매평균가격을 자연키(source+metric+region+baseMonth) 기준 upsert
- `KbLandPriceIndexScheduler`: 매주 토요일 08:10 KST `kbland-price-index` 분산 락(5분위와 별도)으로 실행 — KB Land 최근 2년치 아파트 주간 매매가격지수를 자연키(source+metric+region+baseDate) 기준 upsert. 매월 1일 08:20 KST `kbland-price-index-full` 분산 락으로 20년 전체를 다시 받아 과거 기준일 값 사후 보정을 반영(수동 트리거: `POST /api/admin/scheduler/kbland-price-index/full-refresh`)

### MarketSession (수동 실행 시간대 판단 — `sharedkernel.MarketSession`)
- `DIRECT`: 프리마켓+정규장 전 구간 — 주문 가능 (DST: 17:00~05:00 / 비DST: 18:00~06:00 KST)
- `BLOCKED`: 장마감~프리마켓 전 — 주문 불가 (DST: 05:00~17:00 / 비DST: 06:00~18:00 KST)
- `ManualTradingService.execute()`는 서버에서 세션을 막지 않는다 — PLANNED 생성은 즉시 접수가 아니라 BLOCKED에도 허용(UI 버튼 활성화만 세션 기준). 그래서 마감·개장 배치와 겹칠 수 있고, 이중 실행 검사(같은 사이클 PLANNED/PLACED 존재 시 `AlreadyOrderedTodayException`)를 `AccountBudgetLock` 안에서 저장 직전 재검사한다(배치도 같은 락 안에서 저장 직전 기존 슬롯을 `TradingOrderSlots.excludeExisting`으로 다시 걸러, 수동 실행이 먼저 저장한 슬롯은 중복 저장하지 않고 접수만 한다). 개장 후(`dst.marketOpen()` 이후)이면 AT_OPEN PLANNED 주문(INFINITE는 매도 선접수, VR은 매수·매도 사다리)을 `TradingOrderExecutor.placeAtOpenOrders()`로 즉시 접수한다 — 개장 스케쥴러와 동일하게 BUY cap 보정(`BuyOrderPriceCapper`)을 거친 뒤 접수되며, 반환은 `findPlannedOrPlacedByCycleAndDate`. SELL 가능수량 검증은 같은 계좌·거래일·ticker의 기존 PLANNED/PLACED 예약 수량과 신규 SELL 합계를 사용한다.
- `GET /api/market/session`: UI 수동 실행 버튼 활성화 판단용, `{ session: "DIRECT"|"BLOCKED", isDst: boolean }` 반환

### BuyOrderPriceCapper 보정 주문

INFINITE/PRIVACY/VR 세 전략 모두 대상이며, 단일 진입점 `BuyOrderPriceCapper.capIfNeeded(type, atOpen, ...)`가 `type`(`StrategyType`: INFINITE/PRIVACY/VR)과 `atOpen`(AT_CLOSE/AT_OPEN 스코프) 두 파라미터로 과거 6개 메서드(전략별 3종 × AT_CLOSE/AT_OPEN 쌍)를 대체한다 — AT_OPEN 스코프(`atOpen=true`)는 `findAtOpenPlannedByCycleAndDate`로 AT_OPEN PLANNED만 조회해 동일 사이클의 미도래 AT_CLOSE PLANNED를 건드리지 않는다. `capIfNeeded` 내부는 `cycleOrderStrategies.of(type)`으로 얻은 `CycleOrderStrategy`의 `capBuyOrders(buyOrders, cap, position, vrPosition, ticker, tradeDate)`(캡 초과 BUY 재산정 — INFINITE/VR은 사다리 전체 재구성, PRIVACY는 개별 가격 치환)와 `capsIndividualOrders()`(true=PRIVACY 개별 취소·재저장, false=INFINITE/VR 전체 취소·재저장)로 다형성 분기한다. AT_CLOSE 접수(`TradingOrderExecutor.placeOrders()`)와 AT_OPEN 접수(`placeAtOpenOrders()`) 양쪽에서 `cycleOrderStrategies.of(strategy.type()).needsCapCheck(position, vrPosition)` skip 가드는 호출측 `TradingOrderExecutor.applyCap`에 있다(`capIfNeeded`가 `@Transactional`이라 내부에 두면 skip 케이스마다 빈 트랜잭션이 열리기 때문).

#### INFINITE 전략 (전후반 공통, StrategyType.INFINITE)
- 신규 후보는 `prepareForAllocation`에서 cap 후 base BUY 재산정과 correction BUY 생성을 먼저 수행하며, 이 최종 BUY 총액이 예산 배정 입력이 된다. 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.05`(`PriceCapPolicy`) 초과 시 가격 캡 후 수량 재산정 (`capIfNeeded(StrategyType.INFINITE, ...)`) — 재산정·보정 로직 자체는 `InfiniteCycleOrderStrategy.capBuyOrders()`가 `InfiniteStrategy.buildCappedBuyOrders()`에 위임 (아래 `computeEarlyBuys`/`computeLateBuys`/`CORRECTION_ORDER_COUNT`는 InfiniteStrategy 내부 심볼)
- 전반(buyOrders 2건): `computeEarlyBuys` — cappedAvg/cappedRef 기준 buy①② 재산정, 동가 시 병합
- 후반(buyOrders 1건): `computeLateBuys` — cappedPrice 기준 단일 LOC 수량 재산정
- **보정 주문 (전후반 공통)**: base buy 재산정 후 `CORRECTION_ORDER_COUNT`(=3)회 LOC 1주 추가
  - 가격 = `K / (누적수량 + 1)` (HALF_UP, scale=2) — 매 회 직전까지 추가된 주문 수량 합산 기준
  - 누적수량이 0이면 해당 회차 skip
  - 보정 합계가 원장 예수금(`position.usdDeposit()`)을 넘으면 `InfiniteStrategy.trimCorrectionsToBudget`이 뒤쪽 보정(03→02→01)부터 생략 — base(수량·가격)는 불변, 지정가 합계가 예산 밖이면 allocator가 BUY 전체를 매일 거절해 잔고가 그대로 남고 리버스모드(isFinalRound) 진입도 못 하는 교착이 되기 때문
  - allocator는 live 예산이 전체 BUY를 못 담을 때 `CycleOrderStrategy.fitBuysToBudget`으로 1회 축소(INFINITE=보정 생략, VR=싼 단부터 rung 단위 절단·병합 단 수량 축소, PRIVACY=축소 없음)해 예산 안이면 축소안을 승인·저장하고, base조차 못 담을 때만 거절한다
  - 보정이 생략된 날은 `canSkipOrderComputation`의 correctionComplete(보정 3건 전부 존재)가 성립하지 않아 같은 날 재실행 시 재계산한다 — 같은 leg는 `excludeExisting`이 걸러 중복은 없다
  - 접수 직전 재캡(`capIfNeeded`)의 live 예산 가드: 재캡이 BUY 총액을 늘리지 않으면(PRIVACY 가격 치환 등) allocator 승인 범위 안이라 그대로 반영한다. 늘리면 `capIfNeeded`가 아무것도 반영하지 않고 true를 반환하고, `TradingOrderExecutor.applyCap`이 트랜잭션 밖에서 `계좌 PLANNED BUY 합계 → LiveBalancePort live 주문가능금액` 순으로 조회해 예산(live − 합계 + 자기 스코프 원본 BUY)으로 다시 호출한다 — 예산 초과분은 `fitBuysToBudget`으로 축소, 축소로도 못 담으면 재캡을 생략하고 기존 PLANNED를 유지한다. live 조회가 실패하면 여유 0으로 간주해 예산을 자기 스코프 원본 BUY 합계(allocator 승인액)로 한정해 재캡한다 — 지출은 원본 이하로 유지되고(원장 기준 폴백은 live 초과 그 자체라 택하지 않음), VR은 캡 사다리 앞 n단 가격이 원본 n단 이하라 항상 담긴다. 이 구간(합계→live→재캡 커밋)은 계좌별 JVM 락(`AccountBudgetLock`)으로 직렬화하고 접수는 락 밖에서 병렬 유지 — 합계를 live보다 먼저 읽어 동시 접수분은 이중 차감(보수적)될 뿐이다. 같은 락이 allocator 신규 승인~PLANNED 커밋도 감싼다(배치 `TradingCandidatePlanner`의 계좌별 태스크, 수동 실행 `ManualTradingService`) — 수동 실행은 세션 가드·배치 락 없이 마감(04:30)·개장(22:30) 배치와 겹칠 수 있어, 락이 없으면 같은 계좌의 승인과 재캡이 같은 live 여유분을 이중으로 써 APBK0988(주문가능금액 초과)이 난다. allocator도 같은 이유로 예약 합계를 live보다 먼저 읽는다. 공유 계좌뿐 아니라 단일 전략 계좌도 live < 원장(출금 등)이면 같은 재확장이 생기므로 전략 수와 무관하게 적용된다. VR이 allocator 최우선(`allocationPriority=0`)이라 공유 계좌에서 축소된 VR 사다리가 잔여 예산을 먼저 쓴다(예전엔 VR 전체 거절 시 잔여가 INFINITE/PRIVACY로 넘어감). 백테스트는 VR 사다리·bootstrap이 전략 잔고(pool) 이내로만 생성돼 예산 초과 자체가 없어 축소 훅을 타지 않는다
- 재산정 결과가 모두 비어있으면 BUY 주문 전체 제외 (log.warn)
- INFINITE BUY는 항상 AT_CLOSE라 `capIfNeeded(StrategyType.INFINITE, atOpen=true, ...)`은 실질 no-op — 전략별 분기 대칭성 유지 목적으로만 존재

#### PRIVACY 전략 (StrategyType.PRIVACY)
- 신규 후보는 allocator 전에 cap 초과 BUY 가격만 교체한 금액으로 예산을 검증하며, 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.05`(`PriceCapPolicy`) 초과 시 (`capIfNeeded(StrategyType.PRIVACY, ...)`)
- **수량 재산정 없음** — cap 초과 BUY 주문가만 `currentPrice × 1.05`로 교체, 수량은 FIDA 원본 유지 (`PrivacyCycleOrderStrategy.capBuyOrders()`)
- `TradingOrderExecutor.placeOrders()`: PRIVACY는 `needsCapCheck()`가 기본값(항상 true)이라 position/vrPosition null 가드 대상이 아니며(`applyCap`의 skip 가드는 INFINITE/VR가 override한 `needsCapCheck()`에서만 실질적으로 걸림) 항상 `capIfNeeded` 호출
- `TradingService`: PRIVACY도 `startPrice = price`로 `CycleState`에 전달 (이전에는 `null` → 캡 미적용 버그)
- PRIVACY BUY도 항상 AT_CLOSE라 `capIfNeeded(StrategyType.PRIVACY, atOpen=true, ...)`은 실질 no-op (대칭성 유지 목적)
- `PrivacyCycleOrderStrategy.capsIndividualOrders() = true` — 값이 바뀐 행만 개별 취소·재저장(`BuyOrderPriceCapper.applyIndividualCap`), 변하지 않은 행은 DB에 그대로 둔다

#### VR 전략 (StrategyType.VR)
- bootstrap 주문(LOC+AT_CLOSE, `PriceCapPolicy.capFor(referencePrice)` = referencePrice×1.05)은 사다리 공식과 무관한 별도 산정식이라 보정 대상에서 제외한다 — BUY 중 하나라도 `OrderType.LOC`이면 이번 배치 전체를 bootstrap으로 판별해 skip(`VrCycleOrderStrategy.isVrBootstrapShaped`, private static — `capBuyOrders()` 내부에서 호출)
- 사다리(LIMIT+AT_OPEN) BUY만 보정 대상: cap 초과 시 기존 buyOrders 인자 없이 `VrPosition`+cap만으로 `VrStrategy.buildCappedBuyOrders()`가 사다리 전체를 자기완결적으로 재생성 — poolLimit·pool 한도 내로 자연 재수렴
- AT_CLOSE 스코프(`capIfNeeded(StrategyType.VR, atOpen=false, ...)`)는 사이클+거래일 전체 PLANNED BUY를, AT_OPEN 스코프(`atOpen=true`)는 `findAtOpenPlannedByCycleAndDate`로 사다리 BUY만 조회 — bootstrap이 같은 사이클에 공존해도 스코프 밖이라 자연히 제외됨
- `VrCycleOrderStrategy.capsIndividualOrders()`는 기본값(false) 유지 — 사다리 전체 취소·재저장(`BuyOrderPriceCapper.applyBatchCap`)

### TradingService 기록 테이블 구분
- `orders`: 주문 단위 이벤트 로그 — 실행당 N건 (mainOrders + corrections 모두 저장, order_type/direction/quantity/price/status 포함)
  - `order_leg`: 내부 leg 식별자. 신규 전략 주문은 `INFINITE_EARLY_AVG_BUY`, `VR_BUY_01`, `PRIVACY_SELL_01` 같은 concrete 값을 저장하고, legacy 행은 `UNKNOWN`으로 backfill된다. 브로커 API와 외부 응답 DTO에는 전달하지 않는다.
  - 증권사 접수 실패 → `OrderPort.markFailed(orderId)`로 FAILED 기록 (`TradingOrderExecutor`)
  - 체결 리포트 집계 시 체결 내역 없는 PLACED 주문(미체결) → `OrderPort.markCancelled(orderId)`로 CANCELLED 기록 (`TradingReporter`)
- `cycle_position`: 사이클 단위 포지션 스냅샷 — 실행당 1건 append (`CyclePositionPort.save()`, dedup/UNIQUE 제약 없음). 필드: usd_deposit/avg_price/holdings/closing_price
- `trade_histories`·`portfolio_snapshots` 테이블은 존재하지 않음 — 참조 금지
