# CQ-33 통계·원장 경계 정합성 묶음 — 설계

## 의도

`docs/agents/code-quality-backlog.md` CQ-33의 남은 하위 항목을 백로그 정리 차원에서 닫는다. 사용자가 체감한 증상은 없다. 원칙은 "적은 비용으로 닫기"다. 고칠 값이 큰 것만 코드로 고치고, 되살리기 비싼 것은 한계를 기록한다.

제외 항목:
- 체인 보정 surplus는 083ce5faa에서 해소됐다.
- 종료 처리가 안 된 선행 사이클은 CQ-29에 의존한다.

## 항목별 결정

### 1. VR 롤오버 지연 구멍 — 코드 수정
- 현상: `VrCycleRolloverService`는 옛 사이클을 `endDate = evaluationDate`로 닫는다. 새 사이클의 첫 스냅샷은 롤오버 실행일(createdAt)에 생긴다. 재시도·일시정지·휴장으로 실행일이 evaluationDate보다 늦으면, 그 사이 날짜에는 두 규칙이 겹친다. 옛 사이클은 `TradingStatsService.buildPoints`의 "종료 사이클 탈락" 규칙으로 빠진다. 새 사이클은 아직 스냅샷이 없다. 그래서 전략이 곡선에서 빠진다.
- 결정: "종료 사이클 탈락"(`date.isAfter(endDate)`) 규칙은 **같은 전략에 후속 사이클이 없는 사이클에만** 적용한다. 후속 사이클이 있는 종료 사이클은 마지막 스냅샷 값을 이어 쓴다. 후속 사이클의 스냅샷이 나타나면 기존 "후속 사이클이 있으면 탈락" 규칙이 교체를 처리한다.
- 후속 사이클 판정은 `buildPoints`가 받는 `cycles` 전체(전략별)에서 미리 계산한다. 체인 정렬과 같은 순서 기준(`NEWER_CYCLE`)을 쓴다. "후속 사이클이 있으면 탈락" 규칙은 지금처럼 스냅샷이 들어온 순서대로 쌓는 `chains`를 그대로 쓴다. 따라서 미리 계산한 집합은 탈락 여부만 정하고, 실제 교체는 후속 사이클의 스냅샷이 나타나야 일어난다. (설계자 판단) `cycles`에서 빠진 후속 사이클(soft delete 등)은 없는 것으로 보고, 이때 옛 사이클은 기존처럼 endDate 다음 날부터 빠진다.
- 공백 기간에는 `ChainAdjustment`가 적용되지 않는다. 옛 사이클이 아직 체인의 마지막이기 때문이다. 교체일에는 다음 두 식이 성립하므로 곡선이 연속된다(설계자 판단, 테스트로 확인).
  - 자산: 새 사이클 자산 + surplus ≈ 옛 사이클 endAmount
  - 원금: 새 사이클 원금 − realized + surplus = 옛 사이클 원금
- 부수 효과(수용): 청산 회전이나 일시정지 후 재개(`reopenCycleIfEnded`)로 사이클 사이에 빈 날이 있으면, 그 날들도 옛 사이클의 마지막 값으로 채워진다. 지금은 그 날들에 전략이 곡선에서 빠진다. 시드는 여전히 전략에 묶여 있으므로 이어 쓰는 쪽이 더 맞다고 본다.
- 테스트: 지연 롤오버 시나리오를 추가한다. 옛 사이클의 endDate가 D, 새 사이클의 첫 스냅샷이 D+2다. 이때 D+1 포인트에 옛 사이클의 자산·원금이 포함되는지 확인한다. 후속 사이클이 없는 종료 사이클은 endDate 다음 날부터 빠지는 기존 동작도 고정한다.

### 2. VR 종료금액 시점 혼합 — 문서화만
- 현상: 종료금액은 evaluationDate의 종가에 실행일 매매 후 holdings를 곱하고 실행일 예수금을 더해 계산한다(`VrCycleRolloverService` 약 L120-146). 롤오버가 늦어지면 그 사이 체결이 옛 사이클 평가에 섞인다.
- 결정: 코드는 바꾸지 않는다. evaluationDate 시점 잔고를 재구성하려면 그 사이 체결을 역산해야 한다. 지연도 실패 재시도와 일시정지 때만 생긴다. 해당 계산 블록에 `ponytail:` 주석을 단다. 주석에는 한계(지연 시 그 사이 체결이 섞임)와 업그레이드 경로(evaluationDate 이후 체결 역산)를 적는다.

### 3. 본전 사이클 승률 — 코드 수정
- 결정: 승률 = 수익 사이클 / (수익 사이클 + 손실 사이클)로 한다. `realizedPnl == 0`인 본전 사이클은 분자와 분모 모두에서 뺀다. 수익과 손실 사이클이 모두 0개이면(닫힌 사이클이 전부 본전이거나 아예 없으면) `winRate = null`이다.
- 이유: 본전은 금액이 소수 둘째 자리까지 같아야 하므로 실제로는 매매 없이 닫힌 사이클에서 생긴다. 그런 사이클이 패로 잡히면 승률만 낮추는 잡음이 된다.
- `StrategyTypeStats.winRate` 주석을 "수익 사이클 비율 0~1 (본전 제외, scale 4)"로 고친다. 응답 형식은 바뀌지 않는다. kista-ui는 이미 `winRate: null`(닫힌 사이클 없음)을 처리하므로 수정하지 않는다. 다만 "전부 본전"인 경우도 같은 null 표시를 쓰게 되는데, 이는 수용한다.
- 테스트: 수익 1·손실 1·본전 1이면 0.5000이다. 전부 본전이면 null이다.

### 4. 과거 trade_date 수동 SELL 보정 — 해소로 닫음
- 백로그가 근거로 든 DCA `withdrawnOn`과 `CycleView` `ponytail:` 주석은 131b749b0에서 삭제됐다.
- 남은 차이: `ManualTradeCorrectionService`는 주문을 과거 trade_date로 저장한다. 그런데 스냅샷은 보정 실행 시각(createdAt)에 쌓인다. 그래서 곡선에서는 보정분이 보정한 날에 반영된다. 과거 날짜에는 원래 스냅샷이 없었으므로 이를 허용 한계로 보고 백로그에만 기록한다. 코드는 바꾸지 않는다.

### 5. TtlResultCache 세대 검사 — 코드 수정
- 현상: `TtlResultCache.getOrCompute`에서 계산 시작이 무효화보다 먼저일 수 있다. 그 계산이 무효화 뒤에 끝나면 옛 결과를 넣고, 그 결과가 TTL(통계 5분) 동안 유지된다.
- 결정: 캐시 인스턴스마다 세대 카운터(`AtomicLong`)를 하나 둔다.
  - `invalidateIf`는 키를 지우기 **전에** 세대를 올린다.
  - `getOrCompute`는 loader를 호출하기 전에 세대를 읽어 둔다. 계산이 끝났을 때 세대가 바뀌었으면 결과를 반환만 하고 캐시에 넣지 않는다.
- 키별 세대가 아닌 인스턴스 전역 세대를 쓴다.
  - `invalidateIf`를 호출하는 곳은 `TradingStatsService`와 `BenchmarkService`(L57) 두 곳이다. 각자 자기 인스턴스를 쓰고 무효화 빈도도 낮다.
  - 비용은 무효화 순간에 진행 중이던 다른 키의 계산 결과를 한 번 캐시하지 않는 것뿐이다. 이 한계는 `ponytail:` 주석으로 남긴다.
  - 변경은 `:shared`의 `TtlResultCache`와 그 단위 테스트에 한정한다.
- 테스트: loader 안에서 `invalidateIf`를 호출한다. 그러면 반환값은 그대로이고, 다음 `getOrCompute`는 loader를 다시 호출하는지 확인한다.

### 6. StrategyCapitalChangedEvent 미발행 — 코드 수정
다음 세 경로에서 해당 사용자에 대해 `StrategyCapitalChangedEvent(userId)`를 발행한다. 리스너(`TradingStatsService.onStrategyCapitalChanged`)는 트랜잭션 동기화가 활성이면 afterCommit에, 아니면 즉시 무효화하므로 발행 위치의 트랜잭션 맥락은 그대로 두어도 된다.
- 전략 등록: `StrategyService.register` 경로. 저장이 커밋된 뒤(현재 `NOT_SUPPORTED` 흐름에서 persister 반환 후) 발행한다.
- 계좌 삭제 캐스케이드: `AccountCascadeListener.onAccountDeleted`의 soft delete 처리 안에서 발행한다(REQUIRES_NEW라 afterCommit 무효화).
- 크립토 원장 스냅샷: 발행 위치는 `recordFill`을 호출하는 `CryptoOrderSynchronizer`다(설계자 판단).
  - 이 호출부는 `recordFill`이 true를 돌려주면 이미 `CryptoOrderFilledEvent`를 발행하고 있다. 같은 자리에서 이 이벤트도 함께 발행한다.
  - 트랜잭션 밖이라 무효화가 즉시 일어나지만, `recordFill`의 커밋이 끝난 뒤이므로 문제없다.
- userId 출처(설계자 판단)
  - 크립토: `ctx.account().userId()`에서 꺼낸다. 추가 조회는 없다.
  - 계좌 삭제: `AccountDeletedEvent`는 지금 `accountId`만 가지고 있다. 여기에 `userId` 필드를 추가하고 발행처에서 채운다. 계좌는 이미 soft delete되어 재조회할 수 없기 때문이다.
    - 이 이벤트가 EPR 직렬화 대상이면, 배포 전에 쌓여 있던 미처리 이벤트는 `userId`가 null로 역직렬화될 수 있다. 그래서 null이면 발행을 건너뛴다.
  - 등록: 커맨드나 계좌에 있는 값을 쓴다.
- 테스트: 각 경로의 기존 서비스 단위 테스트에서 이벤트 발행을 검증한다(`verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(userId))`).

### 7. PRIVACY 보유 초과분 — 문서화만
- 동작: 목표 보유량과 실제 보유량의 차이(diff)는 BUY 수량 조정으로만 흡수한다. 부족분은 가장 싼 BUY에 더하고, 초과분은 가장 비싼 BUY부터 줄인다. BUY가 없으면 조정하지 않는다. SELL은 diff로 늘리지 않는다. 실제 보유량을 넘는 SELL은 `capSellQuantitiesToHoldings`가 별도로 잘라낸다.
- 결정: 의도된 동작으로 확정한다. `docs/agents/modules/tradingcore/privacy.md`에 기록하고, 근거는 다음 세 가지다.
  - PRIVACY는 FIDA 매매표를 따르는 전략이라, 매매표에 없는 매도를 내면 신호에서 벗어나고 불필요한 거래·세금이 생긴다.
  - 초과는 주로 수동 매수·반올림에서 생기며 BUY 감액과 매매표의 다음 SELL로 자연 해소된다.
  - 과매도 위험은 SELL cap이 막는다.
- 고정 테스트 이름(`decrease*`, `noBuyTrades`, `sellCap*`)을 함께 적는다.

## 문서·원장 갱신
- `code-quality-backlog.md` CQ-33: 각 하위 항목에 해소 커밋이나 "문서화로 종결"을 표기한다. 4번은 남은 한계 한 줄을 적는다. CQ-29 의존 항목만 남기고 상태를 갱신한다. CQ-29 의존 항목이 남으므로 CQ-33 상태는 "CQ-29 의존 항목만 남음"으로 한다.
- `flows.yml`: 흐름·상태 전이는 바뀌지 않는다. 다만 빌더는 통계 곡선 규칙을 서술한 `desc`가 있는지 grep해, 있으면 1번 변경을 반영한다.

## 비목표
- evaluationDate 시점 잔고 재구성(2번), 과거 날짜 스냅샷 백필(4번), PRIVACY 초과분 매도 도입(7번).
- kista-ui 변경(필요 없음).

## 빌더 재량
- 각 수정의 테스트 배치, 헬퍼 이름, 후속 사이클 집합 계산 방식(1번), userId 확보 방법(6번).
