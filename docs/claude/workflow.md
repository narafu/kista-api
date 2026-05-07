## 스케줄러 실행 흐름
- 스케줄러 기동: 04:00 KST → `AccountRepository.findAllActive()`로 ACTIVE 계좌 목록 조회
- 계좌별 순차 실행: `TradingService.execute(Account, User)` — 한 계좌 실패 시 다음 계좌 계속 (격리)
- 각 계좌: `DstInfo.waitUntilOrderTime()` 대기 (DST=30분, 비DST=90분) → 04:30 KST LOC 주문 접수
- 계좌별 KIS 토큰: `kis_tokens` 테이블에 account_id 기준 독립 관리
- 실행 결과: `UserNotificationPort.notifyTradingReport(user, account, report)` — 계좌봇 > 사용자봇 > 생략
- 오류 시: `NotifyPort.notifyError(e)`로 관리자 알림 + 다음 계좌 계속 실행
- `TradingService`에 INFO 로그 있음 — 계좌별 단계(개장 확인, 잔고, 주문, 체결)마다 찍힘
