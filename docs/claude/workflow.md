## 스케줄러 실행 흐름
- 스케줄러 기동: 04:00 KST → `DstInfo.waitUntilOrderTime()` 대기 (DST=30분, 비DST=90분)
- KIS 호출 시작: DST 기준 04:30 KST (비DST 05:30)
- DB 거래 기록 0건 = 조기 종료 (휴장일 or 잔고 부족) or KIS API 예외
- `TradingService`에 INFO 로그 있음 — 각 단계(토큰, 개장 확인, 잔고, 주문, 체결)마다 찍힘

