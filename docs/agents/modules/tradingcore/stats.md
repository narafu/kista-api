## com.kista.tradingcore.stats (`:trading-core`)

- trading의 공개 계약만 읽는 단방향 소비자다 — trading은 stats를 모른다 (가드: `ModulithArchitectureTest`)
- 원금·`openAmount` 정의 → `docs/agents/modules/tradingcore/trading-formulas.md` "통계 원금". 유지 결정 → `docs/agents/decisions.md` "stats 정책"·"stats 평가액 계산 2종 유지"

### 평가액 계산 2종의 용도 차이
`CyclePosition` 메서드 둘은 목적이 달라 의도적으로 둘이다.
- 투자지수(TWR): `totalAssetAtCloseOrNull()` — 보유분이 있는데 종가가 없으면 null, 그날 평가를 건너뛴다(원가 섞임 방지, 반올림 없음)
- summary 미실현·equity-curve·사이클 성과: `totalAssetWithAvgPriceFallback()` — 종가→평단가→0으로 항상 값(scale=2)
- 그래서 같은 날 같은 포지션이라도 종가가 없으면 두 화면 값이 다를 수 있다

### 원금·통화
- summary/equity-curve/cycles/투자지수는 DCA를 포함한다. 원금 = 사이클 개장 평가(`openAmount`) + 추가·인출 이력 합계, 자산 = 예수금 + 보유 평가액. 매매 계약 `startAmount`는 원금과 정의가 달라 쓰지 않는다
- 원금은 이력으로 날짜별 복원한다(`CapitalLedgerTimeline.restore`) — 추가·인출 전 날짜의 원금·자산이 소급 변경되지 않는다
- 통화가 섞이는 합산 경로(summary·equity-curve·PORTFOLIO 투자지수)는 요청 `?currency=`(기본 USD)와 같은 통화의 계좌만 합산한다(환산 없음, 캐시 키에 통화 포함). 사이클 성과 목록·STRATEGY 스코프는 단일 통화라 필터가 없다

### equity-curve 사이클 교체 체인 보정
- 같은 전략에서 사이클이 교체되면(MAINTAIN·MAX·VR 롤오버) 곡선은 최신 사이클만 합산하되 앞선 종료 사이클의 손익을 보정으로 유지한다(공식은 `TradingStatsService` 주석)
- surplus의 후속 사이클 시작금액은 `openAmount`라 회전 뒤 추가·인출이 과거 곡선을 움직이지 않는다
- 후속 없이 끝난 전략은 종료 다음 날부터 자산에서 빠지지만 체인 전체 손익을 원금에서 계속 빼 둔다 — 곡선 마지막 손익 = 같은 범위 summary 총손익(KRW는 점마다 따로 반올림해 최대 1원 차이). 그래서 끝난 전략만 남은 사용자는 원금선이 음수일 수 있다

### summary 실현손익 정의
- 종료 사이클 + 진행 중 사이클의 매도 확정분(INFINITE 쿼터매도·PRIVACY 매도·DCA SELL)을 합산한다. 진행 중 사이클은 스냅샷이 있으면 `openBasisRealized`, 없으면 0
- 원가 기준은 사이클 개장 시가다 — 등록 전 이익과 이전 사이클에서 승계된 평가익은 실현에 섞이지 않는다
- 마지막 holdings 0이면 end − 원금. holdings > 0(VR 롤오버·재설정 승계분)이면 개장 프리미엄 D(= 개장 평가 − 개장 장부가)를 평균원가법대로 매도 비율만큼 줄이며 스냅샷을 재생하고, 실현 = 마지막 장부가 + 남은 D − 개장 평가 − 이력 합계. 같은 날 매수·매도가 함께 체결되면 순증감으로 근사한다
- 미실현 = 총손익 − 실현이라 합계가 보존된다. 승률·평균 수익률·사이클 성과 목록은 `realizedPnl()`(end − 원금) 기준이고 진행 중 사이클의 실현은 넣지 않는다

### 평균 수익률 정의
종료 사이클별 단순수익률(`realizedPnl() / principal()`, 원금 0 이하 제외)의 단순평균이다. 기간·금액 가중이 없고, 분모가 개장 평가 + 사이클 중 추가·인출 전체라 후반 추가금이 수익률을 희석한다. VR은 롤오버 주기 구간을 사이클 하나로 센다. 정의는 바꾸지 않고 kista-ui 보조문구로 알린다.

### 백테스트
- 엔진은 운영 규칙(원장 예산 `StrategyLedgerBudget.fit`, 가격 캡 `capBuyOrders`, 사이클 교체 `cycleSeedType`, PRIVACY 세션별 배수, VR 등록 정책 허용값)을 따른다 — 운영 규칙을 바꾸면 백테스트도 함께 본다
- 한계: 분할 이전 시작일에 그 시절 원가격으로 `initialAvgPrice`·`vrInitialValue`를 넣으면 분할 수정 시리즈와 어긋난다. 수수료·세금·환전·배당은 미반영(고정 경고 문구로 고지)
