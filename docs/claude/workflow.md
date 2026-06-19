## 스케쥴러 실행 흐름
- 스케쥴러 기동: 04:00 KST → `StrategyPort.findAllActive()`로 ACTIVE 사이클 목록 조회
- context 리스트 빌드: 사이클별 계좌·사용자 조회 (실패 시 해당 사이클 skip + `notifyError`) → `ExecuteTradingUseCase.executeBatch(contexts)` 1회 호출
- `TradingService.executeBatch()`: 고유 ticker 수집 → `KisPricePort.getPrices()` 1회 일괄 조회 → 사이클별 순차 실행 (각 실패 격리 catch + `notifyError`)
- 각 사이클: 휴장 확인 → 잔고 조회 → 현재가(배치 캐시 or 단건 fallback) → 전략 계산 → `orders` PLANNED 저장 → `DstInfo.waitUntilOrderTime()` 대기 (DST=30분, 비DST=90분) → `orders` 조회 → KIS 접수 (PLACED 기록)
- 계좌별 KIS 토큰: `kis_tokens` 테이블에 account_id 기준 독립 관리
- 실행 결과: `UserNotificationPort.notifyTradingReport(user, account, report)` — 사용자봇 미설정 시 생략
- 오류 시: `NotifyPort.notifyError(e)`로 관리자 알림 + 다음 사이클 계속 실행
- `TradingService`에 INFO 로그 있음 — 사이클별 단계(개장 확인, 잔고, 주문, 체결)마다 찍힘

### DstInfo.MarketSession (수동 실행 시간대 판단)
- `DIRECT`: 프리마켓+정규장 전 구간 — 주문 가능 (DST: 17:00~05:00 / 비DST: 18:00~06:00 KST)
- `BLOCKED`: 장마감~프리마켓 전 — 주문 불가 (DST: 05:00~17:00 / 비DST: 06:00~18:00 KST)
- `ManualTradingService.execute()` 수동 실행 진입 시 BLOCKED이면 `IllegalStateException` → 컨트롤러 503
- `GET /api/market/session`: UI 수동 실행 버튼 활성화 판단용, `{ session: "DIRECT"|"BLOCKED", isDst: boolean }` 반환
- kista-ui `NextOrderPreviewCard`: BLOCKED이거나 오늘이 휴장일이면 "지금 실행" 버튼 disabled + title 툴팁

### BuyOrderPriceCapper 보정 주문 (전후반 공통)
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.10` 초과 시 가격 캡 후 수량 재산정
- 전반(buyOrders 2건): `computeEarlyBuys` — cappedAvg/cappedRef 기준 buy①② 재산정, 동가 시 병합
- 후반(buyOrders 1건): `computeLateBuys` — cappedPrice 기준 단일 LOC 수량 재산정
- **보정 주문 (전후반 공통)**: base buy 재산정 후 `CORRECTION_ORDER_COUNT`(=3)회 LOC 1주 추가
  - 가격 = `K / (누적수량 + 1)` (HALF_UP, scale=2) — 매 회 직전까지 추가된 주문 수량 합산 기준
  - 누적수량이 0이면 해당 회차 skip
- 재산정 결과가 모두 비어있으면 BUY 주문 전체 제외 (log.warn)

### TradingService 기록 테이블 구분
- `orders`: 주문 단위 이벤트 로그 — 실행당 N건 (mainOrders + corrections 모두 저장, order_type/direction/quantity/price/status 포함)
- `cycle_position`: 사이클 단위 포지션 스냅샷 — 실행당 1건 append (`CyclePositionPort.save()`, dedup/UNIQUE 제약 없음). 필드: usd_deposit/avg_price/holdings/closing_price
- `trade_histories`·`portfolio_snapshots` 테이블은 존재하지 않음 — 참조 금지
