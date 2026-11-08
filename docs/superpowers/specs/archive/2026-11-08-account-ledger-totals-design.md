# 계좌 상세 "전략 합산" (원장 합산) 설계

## 의도

전략은 원장(`cycle_position`) 기준으로 매매하는데 계좌 상세는 증권사 실잔고만 보여줘서 사용자가 헷갈린다. 계좌 상세의 주 표시를 원장 합산으로 바꾸고, 증권사 실잔고(`GET /api/accounts/{id}/portfolio`)는 "증권사 기준"이라는 보조 표시로 내린다.

## 확정 결정 (2026-10-30, 사용자)

- 표시 1: "예수금 (전략 합산)"은 계좌 안 진행 중 전략들의 최신 `cycle_position.usd_deposit` 합이다.
- 표시 2: "평가금 (전략 합산)"은 같은 전략들의 holdings × 현재가 합이다.
- 사용자 노출 용어는 "전략 합산"이다. 코드와 문서 용어는 "원장"(ledger)이다.

## 이번 설계의 결정 (사용자가 "바로 진행"을 택해 Claude가 정함)

1. **미배정 예수금 한 줄을 넣는다. 공식은 기존 `UnassignedCashCalculator`를 그대로 쓴다.**
   - 공식은 "주문 가능 현금 − 시드를 점유한 전략들의 원장 예수금 합"이다. 주문 가능 현금은 주식 계좌면 `MarginPort.getUsdBuyableAmount`, 코인 계좌면 거래소 `quoteAvailable`이다.
   - 사용자 메모의 "증권사 예수금 − 합"과는 다르다. 매수가능금액은 접수(PLACED) 매수분이 이미 빠진 값이라 이중 차감되므로 보수적인 값이 된다.
   - 이 공식을 고른 이유는 DCA 매수 가용액과 등록 폼 경고가 같은 공식을 써서, 화면마다 숫자가 달라지지 않게 하기 위해서다.
   - UI에는 "주문 가능 기준"이라는 뜻을 함께 전달하도록 요청한다.
   - MOCK 계좌는 값이 null이다(`UnassignedCashCalculator.applies`).
2. **합산 범위는 `UnassignedCashCalculator`와 같게 둔다.**
   - ARCHIVED가 아닌 전략(ACTIVE와 PAUSED)이 대상이고, DCA도 포함한다.
   - ARCHIVED는 보유가 0이고, 남은 예수금은 시드를 점유하지 않으므로 뺀다.
   - 코인 계좌는 계좌 통화(KRW/USDT)로 같은 공식을 쓴다. 응답에 `currency`를 함께 실어 보낸다.
   - 최신 원장이 없는 전략은 예수금 0, 보유 0으로 친다.
3. **API는 새 엔드포인트 `GET /api/accounts/{accountId}/ledger-totals`(trading-core trading 모듈)로 만든다.**
   - Caddy가 `/api/accounts(/.*)?` 전체를 kista-trading으로 직접 보낸다. root를 거치지 않으므로 `shared.contract` 타입은 필요 없다(정찰 결과로 원래 전제를 바로잡음).
   - 계좌 상세 단일 응답은 따로 없다. `/portfolio`는 증권사 잔고를 그대로 옮기는 응답이라 확장하지 않는다. 확장하면 의미가 섞이고, 증권사 호출이 실패할 때 원장 값까지 함께 죽는다.
   - 응답 필드는 `currency`, `deposit`, `evaluation`(nullable), `unassignedCash`(nullable)다.
4. **실패 정책은 fail-open이다(`BrokerCallGuard.tryGet`). 필드 단위로 null을 넣고, 부분합은 보내지 않는다.**
   - 현재가 배치 조회가 실패하거나, 보유가 있는 종목의 가격이 응답 맵에 빠져 있으면 `evaluation = null`이다. 일부만 더한 값은 과소 표시라 내보내지 않는다.
   - 총 보유가 0이면 시세를 호출하지 않고 `evaluation = 0`이다.
   - 미배정 현금 조회가 실패하면 `unassignedCash = null`이다. 원장 예수금은 DB 값이라 항상 나간다.
   - 소유권이 아니면 403, 계좌가 없으면 404다(`requireOwnedAccount`).

## 계산

- 시세 소스: 주식 계좌는 `BrokerPricePort.getPrices(tickers, ref)`, 코인 계좌는 `CryptoExchanges.of(broker).getCurrentPrices(tickers)`를 쓴다.
- 평가금은 Σ(전략 원장 holdings × 해당 ticker 현재가)를 구한 뒤 `Currency.round`로 반올림한다. `CryptoPortfolioService`의 `evalAmount`와 같은 반올림이다.
- 예수금 합 계산은 `UnassignedCashCalculator`와 한 곳에서 공유한다. 기존 계산기를 쪼개 전략과 최신 원장 목록을 한 번만 읽는다.
- 외부 호출(시세·잔고)은 `@Transactional` 밖에서 한다.

## 비목표

- 매매·주문 로직은 바꾸지 않는다.
- 전략별 내역 breakdown은 넣지 않는다(전략 목록 화면에 이미 있다).
- kista-ui 수정은 이 세션에서 하지 않는다. API를 커밋한 뒤 UI 세션에 요청한다.
