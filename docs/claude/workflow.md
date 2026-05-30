## 스케줄러 실행 흐름
- 스케줄러 기동: 04:00 KST → `TradingCyclePort.findAllActive()`로 ACTIVE 사이클 목록 조회
- context 리스트 빌드: 사이클별 계좌·사용자 조회 (실패 시 해당 사이클 skip + `notifyError`) → `ExecuteTradingUseCase.executeBatch(contexts)` 1회 호출
- `TradingService.executeBatch()`: 고유 ticker 수집 → `KisPricePort.getPrices()` 1회 일괄 조회 → 사이클별 순차 실행 (각 실패 격리 catch + `notifyError`)
- 각 사이클: 휴장 확인 → 잔고 조회 → 현재가(배치 캐시 or 단건 fallback) → 전략 계산 → `orders` PLANNED 저장 → `DstInfo.waitUntilOrderTime()` 대기 (DST=30분, 비DST=90분) → `orders` 조회 → KIS 접수 (PLACED 기록)
- 계좌별 KIS 토큰: `kis_tokens` 테이블에 account_id 기준 독립 관리
- 실행 결과: `UserNotificationPort.notifyTradingReport(user, account, report)` — 사용자봇 미설정 시 생략
- 오류 시: `NotifyPort.notifyError(e)`로 관리자 알림 + 다음 사이클 계속 실행
- `TradingService`에 INFO 로그 있음 — 사이클별 단계(개장 확인, 잔고, 주문, 체결)마다 찍힘

### TradingService 기록 테이블 구분
- `trade_histories`: 주문 단위 이벤트 로그 — 실행당 N건 (mainOrders + corrections 각 1행, order_type/direction/quantity/price/status 포함)
- `portfolio_snapshots`: 실행 단위 자산 평가 스냅샷 — 실행당 1건 (holdings/avg_price/current_price/total_asset_usd 등)
- `trading_cycle_history`: 사이클 단위 잔고 스냅샷 — 실행당 1건 append. `UNIQUE(trading_cycle_id, trade_date)` — 동일 trade_date 중복 시 무시(log.warn). 필드: usd_deposit/avg_price/holdings
