# trading 모듈 테스트 패턴

### TradingService execute() 가격 주입 패턴
- `execute(strategy, tradingAccount, userProfile, DstInfo)`는 단건 래퍼로 `executeBatch`에 위임한다 — 현재가는 `TradingPriceFetcher`가 `BrokerPricePort`로 일괄 조회하므로 테스트는 `BrokerPricePort` mock을 stub한다. 배치 경로 테스트의 계좌는 `TradingFixtures.tradingAccount(id, userId)`(=`TradingAccount.from(kisAccount(...))`)·`tossTradingAccount`를 쓰고, 브로커 포트 stub 매처는 `tradingAccount.brokerRef()`를 쓴다. 소유권 검증 경로(`AccountPort.requireOwnedAccount` 등)만 `kisAccount`(Account)를 유지
- `executeBatch(List, DstInfo)` package-private 오버로드 — DstInfo 직접 주입으로 sleep 우회

### TradingService·전략 테스트 패턴
- `TradingServiceTest`는 `when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))` 패턴 사용
- `TradingService` 단위 테스트는 실제 `StrategyLedgerBudget(cyclePositionPort, orderPort, cycleOrderStrategies)`를 주입하고 원장은 `cyclePositionPort.findLatestOne(cycleId)`로 stub한다(미stub이면 `StrategyCycle.startAmount()` 폴백이라 의도한 원장이 아닐 수 있다). 계획 단계는 브로커 잔고·판매가능수량을 조회하지 않으므로 `verifyNoInteractions(sellableQuantityPort)`로 잠근다. 계좌별 병렬 실행은 `TradingCandidatePlanner`가 맡는다 — `TradingCandidatePlanner`(마지막 생성자 인자)와 `TradingService` 양쪽에 `new TradingParallelRunner(0)`(순차 인라인 모드)을 주입해 기존 테스트의 결정적 호출 순서를 보존한다 — 병렬 의미론(동시 상한·그룹 격리·순서 보존·인터럽트 승격)은 별도 `TradingParallelRunnerTest`가 검증한다.
- scheduler leg recovery 테스트는 `Order.withLeg(...)`로 concrete leg를 명시한다. leg 없는 과거(`Order.UNKNOWN_LEG`) 주문은 coarse 슬롯 처리가 없으므로 슬롯·skip 테스트 픽스처로 쓰지 말고, 재주문은 UNKNOWN 원본을 거절하는지만 검증한다.
- INFINITE compute skip 테스트는 correction 포함 complete concrete leg와 partial concrete leg를 모두 둔다. complete는 `buildOrders(...)` 미호출, partial은 호출을 검증해 누락 leg 복구가 막히지 않게 한다. 개장 스케쥴러 회귀는 기존 `AT_CLOSE` BUY만 있을 때 `AT_OPEN` SELL 복구가 막히지 않는지도 검증한다. VR/PRIVACY는 variable ladder라 concrete compute skip을 가정하지 않는다.
- cap 예산 회귀 테스트는 `buildCappedBuyOrders(...)`가 반환하는 base+correction BUY 총액이 원본 BUY보다 커지는 경우를 만들고, `StrategyLedgerBudget`이 최종 총액으로 BUY를 판정하는지 검증한다. 보정 축소(`fitBuysToBudget`) 후에도 base가 원장 잔여를 넘을 때만 거절하고, 축소로 담기면 보정을 덜어낸 BUY가 저장되는지 함께 둔다. VR은 수량을 줄인 새 인스턴스가 원본 BUY 자리에 저장되는지(equals 대조로 조용히 빠지지 않는지) 실제 `VrCycleOrderStrategy`로 검증한다.
- 원장 거절·저장 실패 시 SELL은 유지되고 원래 순서가 보존되는지, 기존 주문이 없는 사이클은 접수·포지션 저장(`cycleHistoryPort.save`)·리포트가 호출되지 않는지(신규 양방향 거절·저장 실패도 같다), 기존 PLANNED/PLACED 주문이 있으면 후속 처리를 유지하는지 검증한다.
- 배치 실패 격리 테스트는 서로 다른 account의 context를 함께 전달하고 원장 조회·`saveAll`·잔고 부족 알림을 각각 예외 처리해 성공 계좌의 저장·접수와 `TradingErrorEvent` 발행(`eventPublisher.publishEvent`)을 함께 검증한다.
- 주식 DCA BUY 가용 테스트: 커널(`DcaCycleOrderStrategyTest`)은 `availableCash` 직접 주입으로 PARTIAL 축소(캡가 기준)·REJECT·null=DATA_UNAVAILABLE·`plan()` 앞 BUY 누적을, 수집기(`DcaPlanInputCollectorTest`)는 `UnassignedUsdCalculator` mock으로 자기 PLANNED/SUBMITTING만 차감·전략당 1회 조회·실패 skip·MOCK 무조회를 검증한다. 주식 BUY를 쓰는 기존 커널·플래너 테스트는 `DcaCycleOrderStrategy.UNLIMITED_CASH`를 넘겨 상한을 끈다. 미배정 USD 계산(`UnassignedUsdCalculatorTest`)은 시드 점유 전략만 빼고 DCA는 제외하는 경계를 잠근다.
- DCA SELL 규칙 테스트(`DcaPlanInputCollectorTest`)는 원장 쪽(보유 − `existingOrders` SELL − 취소된 접수 SELL)과 브로커 쪽(`SellableQuantityPort` − 계좌 예약 SELL)의 min이 규칙 입력이 되는 경계(원장 초과 REJECT/PARTIAL, 브로커가 낮을 때·원장이 낮을 때, 조회 실패 DATA_UNAVAILABLE, MOCK·SELL 없는 전략은 포트 미호출, 규칙 다수여도 1회 조회)를 검증한다. 수집기 생성자에 `SellableQuantityPort`를 주입하고 기본은 충분한 값으로 stub한다.
- `AccountBalance` 테스트 데이터: `usdDeposit = 통합주문가능금액(현금 대용)`; quantity=0이면 usdDeposit만 의미 있음. `AccountBalance(q>0, 전반)` 잔고는 전략 계산 시 최대 4건(LOC매수×2 + LOC매도 + 지정가매도)
