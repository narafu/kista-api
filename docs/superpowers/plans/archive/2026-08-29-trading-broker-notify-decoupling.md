# trading↔broker/notify 순환 의존 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.broker`/`com.kista.notify`(둘 다 CLOSED Modulith 모듈)가 `com.kista.trading`의 도메인 타입을 포트·어댑터 시그니처에 직접 참조하는 3곳을 제거해, `ModulithArchitectureTest`가 검출한 3개 모듈 순환(`broker↔trading`, `notify↔trading`, `broker→trading→notify→broker`)을 없앤다.

**Architecture:** broker/notify가 자기 소유의 얇은 타입을 신설하고 포트 시그니처를 그 타입으로 바꾼다. trading(이미 broker/notify에 의존하는 게 정상 방향인 호출부들)이 타입 매핑을 담당한다. `MockBrokerAdapter`의 trading persistence 직접 접근은 기존 프로젝트 패턴(`AlpacaCalendarAdapter → MarketHolidayStorePort → MarketCalendarPersistenceAdapter`)을 뒤집어 적용 — broker가 포트를 정의하고 trading이 구현한다. trading→notify 11곳은 기존 이벤트 패턴(`CycleEndedEvent` 등)과 동일하게 이벤트 발행으로 전환한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit5/Mockito, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-29-trading-broker-notify-decoupling-design.md` (블로킹 원인이 된 상위 플랜: `docs/superpowers/plans/2026-08-29-spring-modulith-trading-migration.md` Task 4)

## Global Constraints

- 작업 위치: 이미 존재하는 worktree `worktree-modulith-trading-migration`에서 이어서 진행(신규 worktree 불필요 — 상위 trading 이전 플랜의 Task 4가 이 작업 완료를 기다리는 중)
- 커밋 메시지: 한글, Conventional Commit 접두사(`refactor:`), author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고** — 스펙에 이미 반영된 항목(`kisSllType()` 이동, `DstInfo` 부분 복제)은 예외
- 전체 테스트 스위트(`./gradlew test`)는 마지막 Task 완료 후 최종 1회만 — 중간엔 `--tests`로 좁혀서 검증
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용 — GNU sed(`sed -i`)와 다름
- **순환 검증 원칙**(이 계획 전체에 적용): 각 태스크 완료 후 `ModulithArchitectureTest`를 매번 돌릴 필요는 없다(중간 상태는 여전히 순환이 남아있어 계속 FAIL하는 게 정상 — Task 1~7이 전부 끝나야 사라짐). 마지막 Task 8에서만 이 테스트로 최종 확인한다
- **타입 매핑 원칙**: broker 소유 신규 타입(`Direction`, `OrderType`, `PriceSnapshot`, `BrokerBalance`, `OrderInstruction`, `OrderResult`, `CancelInstruction`)은 trading의 대응 타입과 필드가 동일해도 별개 타입이다 — 상호 변환은 항상 trading 쪽 호출부(또는 신설 어댑터)에서 명시적으로 수행하며, broker 쪽 코드에는 trading 타입 import가 절대 없어야 한다
- **⚠️ 실행 순서는 헤딩 번호 순서(1→2→3→4→5→6→7→8)가 아니다 — 반드시 `1 → 4 → 2 → 3 → 5 → 6 → 7 → 8` 순으로 디스패치할 것.** 이유: Task 2(KIS)·Task 3(Toss) 둘 다 `Execution`을 생성하며 그 `direction` 필드가 이미 broker 소유 `Direction` 타입이라고 전제한다(`KisTradingApi.getExecutions`/`TossOrderApi.fetchExecutions`가 `parseDirection(...)`의 결과를 `Execution` 생성자에 그대로 넘김) — 이 필드 타입 자체를 바꾸는 게 Task 4의 일이므로, Task 4가 Task 2/3보다 먼저 끝나 있지 않으면 Task 2/3가 컴파일되지 않는다. `scripts/task-brief`는 헤딩 텍스트로 태스크를 찾으므로(위치 무관) 번호를 문서상 물리적 순서와 다르게 디스패치해도 브리핑 추출 자체는 정상 동작한다 — 컨트롤러가 착각해 1,2,3,4 순으로 그대로 진행하지 않도록 유의할 것

---

### Task 1: broker 신규 타입 7개 정의 + 포트 시그니처 3개 변경

**Files:**
- Create: `src/main/java/com/kista/broker/domain/model/Direction.java`
- Create: `src/main/java/com/kista/broker/domain/model/OrderType.java`
- Create: `src/main/java/com/kista/broker/domain/model/PriceSnapshot.java`
- Create: `src/main/java/com/kista/broker/domain/model/BrokerBalance.java`
- Create: `src/main/java/com/kista/broker/domain/model/OrderInstruction.java`
- Create: `src/main/java/com/kista/broker/domain/model/OrderResult.java`
- Create: `src/main/java/com/kista/broker/domain/model/CancelInstruction.java`
- Modify: `src/main/java/com/kista/broker/domain/port/out/BrokerPricePort.java`
- Modify: `src/main/java/com/kista/broker/domain/port/out/LiveBalancePort.java`
- Modify: `src/main/java/com/kista/broker/domain/port/out/BrokerOrderCorrectionPort.java`

**Interfaces:**
- Produces: `com.kista.broker.domain.model.{Direction, OrderType, PriceSnapshot, BrokerBalance, OrderInstruction, OrderResult, CancelInstruction}`, `BrokerPricePort.getPriceSnapshot(s)`가 broker의 `PriceSnapshot` 반환, `LiveBalancePort.getLiveBalance`가 `BrokerBalance` 반환, `BrokerOrderCorrectionPort.place(OrderInstruction,Account):OrderResult`/`cancel(CancelInstruction,Account):void` — Task 2/3/6이 이 시그니처에 맞춰 구현체를 갱신하고, Task 5가 이 타입들을 trading 쪽에서 매핑

이 태스크는 컴파일이 일시적으로 깨진다(Task 2/3/6이 구현체를 갱신하기 전까지 `KisBrokerAdapter` 등이 옛 시그니처로 `@Override`하려다 실패). **정상이다** — Task 3까지 마치기 전엔 `compileJava`가 통과하지 않는다. 이 태스크에서는 컴파일 확인 생략하고 신규 파일 내용만 작성한다.

- [ ] **Step 1: 신규 타입 7개 생성**

`src/main/java/com/kista/broker/domain/model/Direction.java`:
```java
package com.kista.broker.domain.model;

// 매수/매도 방향 — trading.Order.OrderDirection과 별개(모듈 경계상 공유 불가), 값 집합만 동일
public enum Direction {
    BUY,
    SELL
}
```

`src/main/java/com/kista/broker/domain/model/OrderType.java`:
```java
package com.kista.broker.domain.model;

// 주문 유형 — trading.Order.OrderType과 별개(모듈 경계상 공유 불가), 값 집합만 동일
public enum OrderType {
    LOC,   // Limit On Close: 종가 지정가 주문
    MOC,   // Market On Close: 종가 시장가 주문
    LIMIT  // 일반 지정가 주문
}
```

`src/main/java/com/kista/broker/domain/model/PriceSnapshot.java`:
```java
package com.kista.broker.domain.model;

import java.math.BigDecimal;

// 현재가(current)와 전일종가(prevClose) — trading.PriceSnapshot과 필드 동일한 broker 소유 복제(모듈 경계상 공유 불가)
public record PriceSnapshot(BigDecimal current, BigDecimal prevClose) {}
```

`src/main/java/com/kista/broker/domain/model/BrokerBalance.java`:
```java
package com.kista.broker.domain.model;

import java.math.BigDecimal;

// 잔고 조회 결과 — LiveBalancePort 반환 타입. trading은 이 값으로 자신의 AccountBalance를 구성한다
public record BrokerBalance(
        int holdings,          // 보유 수량
        BigDecimal avgPrice,   // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit  // 통합주문가능금액 (USD)
) {}
```

`src/main/java/com/kista/broker/domain/model/OrderInstruction.java`:
```java
package com.kista.broker.domain.model;

import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;

// 주문 접수 지시 — BrokerOrderCorrectionPort.place() 요청. trading의 Order에서 증권사가 필요로 하는 필드만 추출해 구성한다
public record OrderInstruction(
        Ticker ticker,
        Direction direction,
        OrderType orderType,
        Integer quantity,
        BigDecimal price
) {}
```

`src/main/java/com/kista/broker/domain/model/OrderResult.java`:
```java
package com.kista.broker.domain.model;

// 주문 접수 결과 — BrokerOrderCorrectionPort.place() 응답. trading이 이 값으로 자신의 Order를 order.withPlaced(externalOrderId)로 갱신한다
public record OrderResult(String externalOrderId) {}
```

`src/main/java/com/kista/broker/domain/model/CancelInstruction.java`:
```java
package com.kista.broker.domain.model;

import com.kista.domain.model.strategy.Strategy.Ticker;

// 주문 취소 지시 — BrokerOrderCorrectionPort.cancel() 요청
public record CancelInstruction(Ticker ticker, String externalOrderId) {}
```

- [ ] **Step 2: `BrokerPricePort` 시그니처 변경 (import만 교체, 나머지 무변경)**

`src/main/java/com/kista/broker/domain/port/out/BrokerPricePort.java` 전체를 아래로 교체:
```java
package com.kista.broker.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 현재가·스냅샷 조회 — KIS: 계좌 토큰 사용 / Toss: 공통 API(account 파라미터 무시)
public interface BrokerPricePort {
    BigDecimal getPrice(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account);
    PriceSnapshot getPriceSnapshot(Ticker ticker, Account account);
    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account);
    // 전일종가만 필요한 경우 전용 — Toss는 현재가 API 호출 없이 캔들 API만 호출 (KIS는 현재가와 응답이 묶여 있어 절감 없음)
    BigDecimal getPrevClose(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account);
    // 정규장 확정 종가 — 마감 리포트 전용(getPrevClose와 별도). KIS는 dailyprice, Toss/MOCK은 일봉 캔들(TossCandleApi) 기반 확정 종가 (봉 없으면 라이브가 폴백)
    BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account);
    Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account);
}
```

- [ ] **Step 3: `LiveBalancePort` 시그니처 변경**

`src/main/java/com/kista/broker/domain/port/out/LiveBalancePort.java` 전체를 아래로 교체:
```java
package com.kista.broker.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;

// live 잔고 조회 — KIS/Toss 브로커 어댑터에서 구현
public interface LiveBalancePort {
    BrokerBalance getLiveBalance(Account account, Ticker ticker);
}
```

- [ ] **Step 4: `BrokerOrderCorrectionPort` 시그니처 변경**

`src/main/java/com/kista/broker/domain/port/out/BrokerOrderCorrectionPort.java` 전체를 아래로 교체:
```java
package com.kista.broker.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;

public interface BrokerOrderCorrectionPort {
    void cancel(CancelInstruction instruction, Account account);
    OrderResult place(OrderInstruction instruction, Account account);
}
```

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/broker/domain/model/Direction.java \
        src/main/java/com/kista/broker/domain/model/OrderType.java \
        src/main/java/com/kista/broker/domain/model/PriceSnapshot.java \
        src/main/java/com/kista/broker/domain/model/BrokerBalance.java \
        src/main/java/com/kista/broker/domain/model/OrderInstruction.java \
        src/main/java/com/kista/broker/domain/model/OrderResult.java \
        src/main/java/com/kista/broker/domain/model/CancelInstruction.java \
        src/main/java/com/kista/broker/domain/port/out/BrokerPricePort.java \
        src/main/java/com/kista/broker/domain/port/out/LiveBalancePort.java \
        src/main/java/com/kista/broker/domain/port/out/BrokerOrderCorrectionPort.java
git commit -m "$(cat <<'EOF'
refactor(trading): broker 소유 타입 7개 신설 + 포트 3개 시그니처 변경

BrokerPricePort/LiveBalancePort/BrokerOrderCorrectionPort가 trading의
PriceSnapshot/AccountBalance/Order를 시그니처에 직접 참조하던 것을 broker
소유 타입(PriceSnapshot/BrokerBalance/OrderInstruction+OrderResult+
CancelInstruction)으로 교체 — ModulithArchitectureTest가 검출한
broker↔trading 모듈 순환 제거 작업의 1단계. 이 커밋 시점엔 KIS/Toss
어댑터가 아직 옛 시그니처로 구현돼 있어 컴파일이 깨진다(Task 2/3에서 해소).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 2: KIS 어댑터 갱신

**Files:**
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisBrokerAdapter.java`
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisOrderApi.java`
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java`
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisResponseParser.java`
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisTradingApi.java`
- Test: `src/test/java/com/kista/broker/adapter/out/kis/{KisOrderApiTest, KisPriceApiTest, KisResponseParserTest, KisTradingApiTest}.java`

**Interfaces:**
- Consumes: Task 1의 `com.kista.broker.domain.model.{Direction, OrderType, PriceSnapshot, BrokerBalance, OrderInstruction, OrderResult, CancelInstruction}`
- Produces: KIS 어댑터가 Task 1의 새 포트 시그니처를 구현 — Task 6(MockBrokerAdapter)이 참고할 패턴

- [ ] **Step 1: `KisResponseParser` — `parseDirection`/`formatPrice`를 broker 타입으로 변경**

`src/main/java/com/kista/broker/adapter/out/kis/KisResponseParser.java` 상단 import 교체:
```java
package com.kista.broker.adapter.out.kis;

import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderType;
import com.kista.domain.model.strategy.Strategy.Ticker;
```
(기존 `import com.kista.trading.domain.model.Order;` 삭제)

`parseDirection`/`formatPrice` 메서드 시그니처와 본문(로직 무변경, 타입만 교체):
```java
    // sll_buy_dvsn_cd: 01=매도, 02=매수
    static Direction parseDirection(String sllBuyDvsnCd) {
        return "01".equals(sllBuyDvsnCd) ? Direction.SELL : Direction.BUY;
    }

    // KIS 요청 가격 포맷팅: MOC(시장가)만 "0", LOC/LIMIT(지정가)는 실제 가격 소수 2자리
    static String formatPrice(OrderType type, BigDecimal price) {
        return switch (type) {
            case MOC            -> "0";
            case LOC, LIMIT     -> price.setScale(2, RoundingMode.HALF_UP).toPlainString();
        };
    }
```
나머지 메서드(`parseBd`, `parseIntSafe`, `resolvePrice`, `parseDate`, `streamTickered`)는 무변경.

- [ ] **Step 2: `KisOrderApi.place`/`cancel`을 `OrderInstruction`/`CancelInstruction`/`OrderResult` 기반으로 재작성**

`src/main/java/com/kista/broker/adapter/out/kis/KisOrderApi.java` 상단 import 교체:
```java
package com.kista.broker.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
```
(기존 `import com.kista.trading.domain.model.Order;` 삭제)

`place`/`cancel` 전체를 아래로 교체:
```java
    public OrderResult place(OrderInstruction instruction, Account account) {
        String trId = instruction.direction() == Direction.BUY ? BUY_TR_ID : SELL_TR_ID;
        String[] acctParts = splitAccountNo(account);
        String cano = acctParts[0];
        String acntPrdtCd = acctParts[1];

        // autotrade 성공 패턴과 동일한 필드 순서 및 raw JSON String 포맷으로 전송
        String body = """
                {
                    "ORD_SVR_DVSN_CD": "0",
                    "CANO": "%s",
                    "ACNT_PRDT_CD": "%s",
                    "ORD_DVSN": "%s",
                    "OVRS_EXCG_CD": "%s",
                    "PDNO": "%s",
                    "OVRS_ORD_UNPR": "%s",
                    "ORD_QTY": "%s",
                    "SLL_TYPE": "%s",
                    "CTAC_TLNO": "",
                    "MGCO_APTM_ODNO": ""
                }""".formatted(
                cano,
                acntPrdtCd,
                resolveOrderDvsn(instruction.orderType()),
                exchangeRegistry.ovrsExcgCd(instruction.ticker()),
                instruction.ticker().name(),
                KisResponseParser.formatPrice(instruction.orderType(), instruction.price()),
                instruction.quantity(),
                kisSllType(instruction.direction()));

        OrderResponse response = kisHttpClient.post(trId, PATH, account, body, OrderResponse.class);

        // rt_cd != "0" = KIS 비즈니스 오류 (HTTP 200이어도 실패) — msg_cd/msg1 포함해 예외 발생
        if (response == null || !"0".equals(response.rtCd())) {
            String code = response != null ? response.msgCd() : "N/A";
            String msg  = response != null ? response.msg1()  : "응답 없음";
            throw new KisApiException("KIS 주문 실패 [" + code + "]: " + msg, null);
        }

        String odno = response.output() != null ? response.output().odno() : null;
        return new OrderResult(odno);
    }

    public void cancel(CancelInstruction instruction, Account account) {
        String[] acctParts = splitAccountNo(account);
        String cano = acctParts[0];
        String acntPrdtCd = acctParts[1];

        // place()와 동일하게 raw JSON String으로 전송 — Map+Jackson 직렬화 시 EGW00202 발생
        String body = """
                {
                    "CANO": "%s",
                    "ACNT_PRDT_CD": "%s",
                    "OVRS_EXCG_CD": "%s",
                    "PDNO": "%s",
                    "ORGN_ODNO": "%s",
                    "RVSE_CNCL_DVSN_CD": "02",
                    "ORD_QTY": "0",
                    "OVRS_ORD_UNPR": "0",
                    "MGCO_APTM_ODNO": "",
                    "ORD_SVR_DVSN_CD": "0"
                }""".formatted(
                cano,
                acntPrdtCd,
                exchangeRegistry.ovrsExcgCd(instruction.ticker()),
                instruction.ticker().name(),
                instruction.externalOrderId());

        kisHttpClient.post(CANCEL_TR_ID, CANCEL_PATH, account, body, Void.class);
    }

    // KIS SLL_TYPE 파라미터: 매도=00, 매수="" (빈 문자열) — trading.Order.OrderDirection.kisSllType()에 있던 KIS 전용 인코딩을 broker로 이동
    private static String kisSllType(Direction direction) {
        return direction == Direction.SELL ? "00" : "";
    }
```
`resolveOrderDvsn(OrderType)` 헬퍼는 파일 안에 이미 있을 가능성이 높다 — `grep -n "resolveOrderDvsn" src/main/java/com/kista/broker/adapter/out/kis/KisOrderApi.java`로 확인 후, 파라미터 타입이 `Order.OrderType`이면 `OrderType`으로, import도 함께 교체한다.

- [ ] **Step 3: `KisPriceApi` — `PriceSnapshot` import만 교체**

`src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java`의 import 한 줄만 교체:
```java
import com.kista.broker.domain.model.PriceSnapshot;
```
(`import com.kista.trading.domain.model.PriceSnapshot;` 삭제). 필드명이 동일(`current`, `prevClose`)이라 본문 로직은 전부 무변경.

- [ ] **Step 4: `KisTradingApi` — `getBalance` 반환 타입 변경**

`src/main/java/com/kista/broker/adapter/out/kis/KisTradingApi.java`의 import 교체:
```java
import com.kista.broker.domain.model.BrokerBalance;
```
(`import com.kista.trading.domain.model.AccountBalance;` 삭제)

`getBalance` 메서드:
```java
    public BrokerBalance getBalance(Account account, Ticker ticker) {
        HoldingResult holding = fetchHolding(account, ticker);
        BigDecimal usdDeposit = getUsdBuyableAmount(account);
        BigDecimal avgPrice = holding.quantity() > 0 ? holding.avgPrice() : null;
        return new BrokerBalance(holding.quantity(), avgPrice, usdDeposit);
    }
```
`Execution` 생성부(`getExecutions` 내부, `KisResponseParser.parseDirection(item.sllBuyDvsnCd())` 호출부)는 **무변경** — `parseDirection`이 이제 `Direction`을 반환하고 `Execution`의 필드 타입도 Task 4에서 `Direction`으로 바뀌므로 타입이 자연히 맞는다.

- [ ] **Step 5: `KisBrokerAdapter` — 위임 시그니처 갱신**

`src/main/java/com/kista/broker/adapter/out/kis/KisBrokerAdapter.java`에서 import 교체:
```java
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.PriceSnapshot;
```
(`import com.kista.trading.domain.model.AccountBalance;`, `import com.kista.trading.domain.model.PriceSnapshot;` 삭제)

관련 메서드 4개를 아래로 교체(순수 위임, 로직 무변경):
```java
    @Override
    public void cancel(CancelInstruction instruction, Account account) {
        kisOrderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, Account account) {
        return kisOrderApi.place(instruction, account);
    }
```
```java
    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return kisPriceApi.getPriceSnapshot(ticker, account);
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return kisPriceApi.getPriceSnapshots(tickers, account);
    }
```
```java
    @Override
    public BrokerBalance getLiveBalance(Account account, Ticker ticker) {
        return kisTradingApi.getBalance(account, ticker);
    }
```
클래스 선언부의 `implements BrokerAdapterPort, ...` 목록·나머지 메서드는 무변경.

- [ ] **Step 6: 테스트 4개 갱신**

`src/test/java/com/kista/broker/adapter/out/kis/KisResponseParserTest.java` — import 교체(`com.kista.trading.domain.model.Order` 삭제, `com.kista.broker.domain.model.Direction`/`OrderType` 추가) + 아래 치환:
```bash
sed -i '' \
  -e '/^import com\.kista\.trading\.domain\.model\.Order;$/c\
import com.kista.broker.domain.model.Direction;\
import com.kista.broker.domain.model.OrderType;' \
  -e 's/Order\.OrderDirection\./Direction./g' \
  -e 's/Order\.OrderType\./OrderType./g' \
  src/test/java/com/kista/broker/adapter/out/kis/KisResponseParserTest.java
```

`src/test/java/com/kista/broker/adapter/out/kis/KisPriceApiTest.java` — import 한 줄만:
```bash
sed -i '' 's/^import com\.kista\.trading\.domain\.model\.PriceSnapshot;$/import com.kista.broker.domain.model.PriceSnapshot;/' \
  src/test/java/com/kista/broker/adapter/out/kis/KisPriceApiTest.java
```

`src/test/java/com/kista/broker/adapter/out/kis/KisTradingApiTest.java` — import + `Order.OrderDirection.SELL/BUY` → `Direction.SELL/BUY` (129, 146행):
```bash
sed -i '' \
  -e 's/^import com\.kista\.trading\.domain\.model\.Order;$/import com.kista.broker.domain.model.Direction;/' \
  -e 's/Order\.OrderDirection\./Direction./g' \
  src/test/java/com/kista/broker/adapter/out/kis/KisTradingApiTest.java
```

`src/test/java/com/kista/broker/adapter/out/kis/KisOrderApiTest.java` — 이 파일은 `Order` 객체를 생성자로 직접 만들어 `api.place(order, ACCOUNT)`/`api.cancel(order, ACCOUNT)`를 호출하던 구조라 sed로 안 되고 각 테스트를 `OrderInstruction`/`CancelInstruction` 생성으로 다시 써야 한다. 전체 파일을 아래로 교체:
```java
package com.kista.broker.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisOrderApi 주문 처리 검증")
class KisOrderApiTest {

    @Mock KisHttpClient kisHttpClient;
    @Spy KisExchangeRegistry exchangeRegistry = new KisExchangeRegistry();
    @InjectMocks KisOrderApi api;

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", null,
            Account.Broker.KIS, null
    );

    @Test
    @DisplayName("BUY+LOC: TTTT1002U 사용, ORD_DVSN=34, 실제 가격 전달(지정가이므로 0 금지)")
    void place_buyLoc_usesBuyTrIdAndOrdDvsn34() {
        BigDecimal locPrice = new BigDecimal("25.50");
        OrderInstruction instruction = new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.LOC, 10, locPrice);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(ok);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.place(instruction, ACCOUNT);

        verify(kisHttpClient).post(eq("TTTT1002U"), anyString(), eq(ACCOUNT), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).contains("\"ORD_DVSN\": \"34\"");
        assertThat(bodyCaptor.getValue()).contains("\"OVRS_ORD_UNPR\": \"25.50\"");
    }

    @Test
    @DisplayName("BUY+MOC: ORD_DVSN=33, 가격=0")
    void place_buyMoc_usesOrdDvsn33() {
        OrderInstruction instruction = new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.MOC, 5, BigDecimal.ZERO);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(ok);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.place(instruction, ACCOUNT);

        verify(kisHttpClient).post(anyString(), anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).contains("\"ORD_DVSN\": \"33\"");
        assertThat(bodyCaptor.getValue()).contains("\"OVRS_ORD_UNPR\": \"0\"");
    }

    @Test
    @DisplayName("BUY+LIMIT: ORD_DVSN=00, 실제 가격 전달")
    void place_buyLimit_usesActualPrice() {
        BigDecimal limitPrice = new BigDecimal("25.50");
        OrderInstruction instruction = new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.LIMIT, 3, limitPrice);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(ok);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.place(instruction, ACCOUNT);

        verify(kisHttpClient).post(anyString(), anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).contains("\"ORD_DVSN\": \"00\"");
        assertThat(bodyCaptor.getValue()).contains("\"OVRS_ORD_UNPR\": \"25.50\"");
    }

    @Test
    @DisplayName("SELL: TTTT1006U 사용")
    void place_sell_usesSellTrId() {
        OrderInstruction instruction = new OrderInstruction(Ticker.SOXL, Direction.SELL, OrderType.LOC, 8, BigDecimal.ZERO);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(ok);

        api.place(instruction, ACCOUNT);

        verify(kisHttpClient).post(eq("TTTT1006U"), anyString(), eq(ACCOUNT), any(), any());
    }

    @Test
    @DisplayName("응답 ODNO → externalOrderId 반환")
    void place_responseWithOdno_returnsExternalOrderId() {
        OrderInstruction instruction = new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.LOC, 10, BigDecimal.ZERO);
        KisOrderApi.OrderResponse response =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD123"));
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(response);

        OrderResult result = api.place(instruction, ACCOUNT);

        assertThat(result.externalOrderId()).isEqualTo("ORD123");
    }

    @Test
    @DisplayName("KIS 비즈니스 오류(rt_cd!=0): KisApiException 발생")
    void place_kisErrorResponse_throwsKisApiException() {
        OrderInstruction instruction = new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.LOC, 10, BigDecimal.ZERO);
        KisOrderApi.OrderResponse errorResponse =
                new KisOrderApi.OrderResponse("1", "EGW00202", "GW라우팅 중 오류가 발생했습니다.", null);
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(errorResponse);

        assertThatThrownBy(() -> api.place(instruction, ACCOUNT))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("EGW00202");
    }

    @Test
    @DisplayName("cancel: TTTT1004U + CANCEL_PATH 호출, RVSE_CNCL_DVSN_CD=02, ORGN_ODNO=기존주문번호")
    void cancel_sendsCorrectParameters() {
        CancelInstruction instruction = new CancelInstruction(Ticker.SOXL, "ORD_123");
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any())).thenReturn(null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.cancel(instruction, ACCOUNT);

        verify(kisHttpClient).post(
                eq("TTTT1004U"),
                eq("/uapi/overseas-stock/v1/trading/order-rvsecncl"),
                eq(ACCOUNT), bodyCaptor.capture(), any());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"RVSE_CNCL_DVSN_CD\": \"02\"");
        assertThat(body).contains("\"ORGN_ODNO\": \"ORD_123\"");
        assertThat(body).contains("\"ORD_QTY\": \"0\"");
        assertThat(body).contains("\"OVRS_ORD_UNPR\": \"0\"");
    }

    @Test
    @DisplayName("cancel: KIS 오류(RuntimeException) 전파")
    void cancel_kisError_propagatesException() {
        CancelInstruction instruction = new CancelInstruction(Ticker.SOXL, "ORD_456");
        when(kisHttpClient.post(anyString(), anyString(), any(Account.class), any(), any()))
                .thenThrow(new RuntimeException("KIS 오류"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> api.cancel(instruction, ACCOUNT));
    }
}
```
(원본에 있던 `place_responseWithOdno_returnsExternalOrderId`의 `result.status()` 단언은 삭제됨 — `OrderResult`엔 `status` 필드가 없다. `Order`의 상태 갱신은 이제 trading 호출부의 책임(Task 5)이라 이 테스트의 관심사 밖)

- [ ] **Step 7: compileJava — KIS 관련 컴파일 에러만 남았는지 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `kis` 패키지 관련 에러는 사라지고, `toss`/`mock` 패키지(Task 3/6에서 처리)와 trading 호출부(Task 5) 관련 에러만 남아있어야 한다. KIS 관련 에러가 남아있으면 이 태스크 안에서 해결.

- [ ] **Step 8: KIS 테스트 4개 실행**

```bash
./gradlew test --tests 'com.kista.broker.adapter.out.kis.KisOrderApiTest' \
  --tests 'com.kista.broker.adapter.out.kis.KisPriceApiTest' \
  --tests 'com.kista.broker.adapter.out.kis.KisResponseParserTest' \
  --tests 'com.kista.broker.adapter.out.kis.KisTradingApiTest'
```
Expected: 전부 PASS. `compileTestJava`가 다른 패키지(toss/mock 테스트, trading 호출부 테스트) 에러로 실패하면 `--tests` 필터가 무의미해지므로, 이 시점엔 `compileTestJava` 전체가 안 될 수 있다(Task 3/5/6 전까지는 정상) — 이 경우 `./gradlew :compileTestJava` 대신 IDE나 `javac` 없이는 개별 테스트를 못 돌릴 수 있음을 인지하고, 안 되면 이 Step은 스킵하고 보고서에 "Task 3/5/6 완료 후 재확인 필요"로 남긴다.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/broker/adapter/out/kis/ src/test/java/com/kista/broker/adapter/out/kis/
git commit -m "$(cat <<'EOF'
refactor(trading): KIS 어댑터를 broker 소유 타입 기반으로 갱신

KisBrokerAdapter/KisOrderApi/KisPriceApi/KisResponseParser/KisTradingApi가
Task 1에서 신설한 broker 소유 타입(Direction/OrderType/PriceSnapshot/
BrokerBalance/OrderInstruction/OrderResult/CancelInstruction)을 쓰도록
갱신. KisOrderApi.place/cancel을 Order 대신 OrderInstruction/
CancelInstruction/OrderResult로 재작성하면서 kisSllType() 인코딩(매도=00,
매수="")을 trading의 Order.OrderDirection에서 broker 내부 private
메서드로 이동 — KIS 전용 로직이 trading 도메인에 있던 기존 결함도 해소.
대응 테스트 4개 갱신. 로직 변경 없음(순수 타입 치환 + 위치 이동).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 4: `Execution`/`DailyTransaction` — `Order.OrderDirection` 필드를 broker의 `Direction`으로 교체

**Files:**
- Modify: `src/main/java/com/kista/broker/domain/model/Execution.java`
- Modify: `src/main/java/com/kista/broker/domain/model/DailyTransaction.java`

**Interfaces:**
- Consumes: Task 1의 `com.kista.broker.domain.model.Direction`
- Produces: `Execution.direction()`/`DailyTransaction.direction()`이 `Direction` 반환 — Task 2에서 이미 `KisTradingApi`의 `Execution` 생성부가 이 타입에 맞춰 컴파일되도록 준비됨(무변경으로 통과), Task 3(Toss)도 동일하게 맞춰야 함

`Execution`이 구현하던 `AccountBalance.Fill`(addendum 1b, commit `aa038a11`)은 이번에 **제거한다** — `Fill.direction()`이 trading의 `Order.OrderDirection`을 반환해야 해서 결국 broker가 trading을 참조하게 되는 구조였다(Task 4 리뷰에서 재발 확인됨, 스펙 "배경" 절 참고). `AccountBalance.applyExecutions`를 호출하는 쪽(trading의 `TradingReporter` 등, legacy `AdminTradeCorrectionService`, `BacktestEngine`)이 이제 `Execution`을 `AccountBalance.Fill`로 직접 감싸는 작은 매핑을 담당한다 — 이건 Task 5의 몫이다.

- [ ] **Step 1: `Execution.java` 수정**

전체를 아래로 교체:
```java
package com.kista.broker.domain.model;

import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Execution(
        LocalDate tradeDate,            // 체결일
        Ticker ticker,                  // 종목 코드
        Direction direction,            // 매수/매도 방향
        int quantity,                   // 체결 수량
        BigDecimal price,               // 체결 단가 (USD)
        BigDecimal amountUsd,           // 체결 금액 (USD) = price × quantity
        String externalOrderId          // 증권사 주문 번호 (KIS: ODNO)
) {
    // 관리자 수동 체결 보정용 — amountUsd = price × quantity 내부 계산
    public static Execution ofManualFill(LocalDate tradeDate, Ticker ticker, Direction direction,
                                         int quantity, BigDecimal price, String externalOrderId) {
        return new Execution(tradeDate, ticker, direction, quantity, price,
                price.multiply(BigDecimal.valueOf(quantity)), externalOrderId);
    }
}
```
(`import com.kista.trading.domain.model.AccountBalance;`, `import com.kista.trading.domain.model.Order;`, `implements AccountBalance.Fill` 전부 삭제)

- [ ] **Step 2: `DailyTransaction.java` 수정**

전체를 아래로 교체:
```java
package com.kista.broker.domain.model;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;

public record DailyTransaction(
        String tradeDate,                    // 매매일자 (trad_dt)
        String settlementDate,               // 결제일자 (sttl_dt) — Toss 미제공 시 null
        Direction direction,                 // 매도/매수 방향 (sll_buy_dvsn_cd: 01=매도, 02=매수)
        Ticker ticker,                       // 종목코드 (pdno)
        String symbolName,                   // 종목명 (ovrs_item_name)
        int quantity,                        // 체결수량 (ccld_qty)
        BigDecimal price,                    // 해외주식체결단가 (ovrs_stck_ccld_unpr)
        BigDecimal tradeAmountUsd,           // 거래외화금액 (tr_frcr_amt2)
        BigDecimal settlementAmountKrw,      // 원화정산금액 (wcrc_excc_amt) — Toss 미제공 시 null
        BigDecimal exchangeRate,             // 등록환율 (erlm_exrt) — Toss 미제공 시 null
        String currency                      // 통화코드 (crcy_cd)
) {}
```
(`import com.kista.trading.domain.model.Order;` 삭제, `Order.OrderDirection` → `Direction`)

- [ ] **Step 3: `AccountBalance.Fill` 인터페이스 삭제 확인**

`AccountBalance.java`(`src/main/java/com/kista/trading/domain/model/AccountBalance.java`)의 `Fill` 인터페이스는 이번 태스크에서 건드리지 않는다 — Task 5에서 `Fill`을 구현하는 매핑 어댑터를 trading 쪽 호출부에 추가할 때 그대로 재사용한다(인터페이스 자체는 이미 trading 소유라 문제 없음, 문제는 broker의 `Execution`이 이걸 구현하던 부분이었다).

- [ ] **Step 4: `DailyTransaction`/`Execution` 관련 컴파일·테스트 확인**

```bash
grep -rln "DailyTransaction\b" src/test/java --include="*.java"
grep -rln "\bExecution\b" src/test/java --include="*.java" | xargs grep -l "AccountBalance\.Fill\|implements.*Fill"
```
두 번째 명령 결과가 있으면(Execution이 Fill을 구현한다고 가정한 테스트) 해당 파일에서 `implements AccountBalance.Fill` 전제를 제거 — 이 시점엔 아직 Task 5가 안 끝나 컴파일이 깨질 수 있으니 실패 목록만 기록하고 넘어간다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/broker/domain/model/Execution.java src/main/java/com/kista/broker/domain/model/DailyTransaction.java
git commit -m "$(cat <<'EOF'
refactor(trading): Execution/DailyTransaction의 Order.OrderDirection을 broker Direction으로 교체

addendum 1b(commit aa038a11)에서 도입한 Execution implements
AccountBalance.Fill을 제거한다 — Fill.direction()이 trading의
Order.OrderDirection을 반환해야 해서 결국 broker→trading 참조가 남는
구조였음(Task 4 모듈 선언 시도에서 순환 재발로 확인됨). direction 필드를
broker 소유 Direction으로 바꿔 완전히 독립시키고, AccountBalance.Fill
구현은 trading 쪽 호출부(Task 5)로 옮긴다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 3: Toss 어댑터 갱신

**Files:**
- Modify `src/main/java/com/kista/broker/adapter/out/toss/TossBrokerAdapter.java`
- Modify `src/main/java/com/kista/broker/adapter/out/toss/TossOrderApi.java`
- Modify `src/main/java/com/kista/broker/adapter/out/toss/TossHoldingsApi.java`
- Modify `src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java`
- Modify `src/main/java/com/kista/adapter/out/marketdata/CommonMarketPriceFeed.java` (**추가 발견** — TossPriceApi가 이 인터페이스를 구현하므로 함께 바꿔야 컴파일된다. 아래 "왜 이 파일도 함께 바뀌는가" 참고)
- Modify `src/test/java/com/kista/broker/adapter/out/toss/TossOrderApiTest.java`
- Modify `src/test/java/com/kista/broker/adapter/out/toss/TossPriceApiTest.java`
- Modify `src/test/java/com/kista/broker/adapter/out/toss/TossHoldingsApiTest.java`
- `src/test/java/com/kista/broker/adapter/out/toss/TossBrokerAdapterTest.java` — 변경 불필요 (아래 이유 참고)

**Interfaces:**
- Consumes: Task 1의 broker 소유 타입 7종, Task 2와 동일 패턴(place/cancel 재작성, PriceSnapshot/BrokerBalance import 교체)
- Produces: Toss 어댑터가 새 포트 시그니처를 구현 — Task 6(MockBrokerAdapter)이 `CommonMarketPriceFeed`를 통해 이 타입 변경의 영향을 받음(아래 참고)

#### 왜 `CommonMarketPriceFeed`도 함께 바뀌는가

`TossPriceApi`는 `BrokerPricePort`(계좌 인자 있음, broker 소유 `PriceSnapshot`으로 바뀜) 뿐 아니라 계좌 무관 레거시 인터페이스 `com.kista.adapter.out.marketdata.CommonMarketPriceFeed`도 구현한다(`getPriceSnapshot(Ticker)`/`getPriceSnapshots(List<Ticker>)` — account 파라미터 없는 시그니처, `MockBrokerAdapter`가 이 인터페이스로 `TossPriceApi` 빈을 재사용). 이 인터페이스는 현재 `com.kista.trading.domain.model.PriceSnapshot`을 반환 타입으로 선언한다. `TossPriceApi.getPriceSnapshot(Ticker)`의 반환 타입을 broker 소유 `PriceSnapshot`으로 바꾸면, `implements CommonMarketPriceFeed`의 메서드 시그니처가 더 이상 일치하지 않아 컴파일 오류("does not override abstract method")가 난다.

`grep -rn "CommonMarketPriceFeed" src/main/java/`로 확인한 결과 이 인터페이스는 `TossPriceApi`(구현)와 `MockBrokerAdapter`(소비, 필드 타입)에서만 쓰인다 — 트레이딩 도메인 코드가 이 인터페이스를 직접 참조하는 곳은 없다. 즉 이 인터페이스는 사실상 broker 내부(Toss/Mock)의 "계좌 무관 공용 시세" 개념 전용이므로, import를 broker 소유 `PriceSnapshot`으로 바꿔도 안전하다.

**주의**: `MockBrokerAdapter`(Task 6 소관)는 이번 Task 3에서 손대지 않는다. 하지만 `CommonMarketPriceFeed`의 타입이 바뀌면 `MockBrokerAdapter`의 `priceFeed.getPriceSnapshot(ticker)` 호출부(현재 trading `PriceSnapshot` 반환을 기대)가 broker `PriceSnapshot`을 받게 되어, `MockBrokerAdapter` 자신의 `BrokerPricePort.getPriceSnapshot(Ticker, Account)` 오버라이드도 같은 시점에 broker 타입으로 바뀌어야 컴파일된다 — Task 3 완료 시점에 `MockBrokerAdapter`가 있는 상태에서 `compileJava`를 돌리면 Task 6이 아직 안 왔을 경우 컴파일이 깨질 수 있음을 인지하고, Task 3와 Task 6을 같은 세션에서 순서대로 처리할 것.

- [ ] **Step 1: `TossBrokerAdapter.java` 수정**

Before:
```java
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.broker.domain.model.*;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.*;
import com.kista.broker.domain.port.out.*;
```
```java
    @Override
    public void cancel(com.kista.trading.domain.model.Order order, Account account) {
        tossOrderApi.cancel(order, account);
    }

    @Override
    public com.kista.trading.domain.model.Order place(com.kista.trading.domain.model.Order order, Account account) {
        return tossOrderApi.place(order, account);
    }
```
```java
    @Override
    public AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return tossHoldingsApi.getBalance(account, ticker);
    }
```

After:
```java
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.broker.domain.model.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.*;
import com.kista.broker.domain.port.out.*;
```
(`com.kista.trading.domain.model.AccountBalance`/`PriceSnapshot` import 2줄 삭제 — 파일 상단에 이미 있는 `import com.kista.broker.domain.model.*;` 와일드카드가 `Direction`/`OrderType`/`PriceSnapshot`/`BrokerBalance`/`OrderInstruction`/`OrderResult`/`CancelInstruction`을 모두 커버하므로 별도 개별 import 불필요. `getPriceSnapshot`/`getPriceSnapshots` 메서드 본문은 무변경 — import만 바뀌면 그 안의 `PriceSnapshot`이 자동으로 broker 소유 타입을 가리킨다.)
```java
    @Override
    public void cancel(CancelInstruction instruction, Account account) {
        tossOrderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, Account account) {
        return tossOrderApi.place(instruction, account);
    }
```
```java
    @Override
    public BrokerBalance getLiveBalance(Account account, Ticker ticker) {
        return tossHoldingsApi.getBalance(account, ticker);
    }
```

- [ ] **Step 2: `TossOrderApi.java` 수정**

Before:
```java
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.TossApiException;
```
```java
    public Order place(Order order, Account account) {
        // Toss는 MARKET 주문 미지원 — MOC도 LIMIT+CLS로 대체
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", order.ticker().name());              // 종목 코드 (예: SOXL)
        body.put("side", order.direction().name());             // BUY / SELL
        body.put("orderType", "LIMIT");                         // Toss 지원 타입: LIMIT만
        body.put("timeInForce", resolveTimeInForce(order.orderType())); // CLS(장마감) or DAY(정규장)
        body.put("quantity", order.quantity());
        body.put("price", resolvePrice(order.orderType(), order.price()));

        // Toss API 응답: {"result": {"orderId": "...", "clientOrderId": "..."}} — TossResult 언랩
        OrderResponse resp = TossResponseParser.unwrap(
                tossHttpClient.post(ORDER_PATH, account, body,
                        new ParameterizedTypeReference<TossResult<OrderResponse>>() {}));

        // orderId 없으면 비즈니스 실패 처리
        if (resp.orderId() == null) {
            throw new TossApiException("Toss 주문 실패: 응답에 orderId 없음", null);
        }

        // id=null — KIS와 동일하게 호출자가 DB 저장(plan/markPlaced) 처리
        return order.withPlaced(resp.orderId());
    }

    public void cancel(Order order, Account account) {
        // POST /api/v1/orders/{externalOrderId}/cancel — DELETE 아님 (Toss 공식 스펙, cancelOrder)
        tossHttpClient.post(ORDER_PATH + "/" + order.externalOrderId() + "/cancel", account, Map.of(), Void.class);
    }
```
```java
        Order.OrderDirection direction = "BUY".equals(order.side())
                ? Order.OrderDirection.BUY
                : Order.OrderDirection.SELL;

        return Optional.of(new Execution(tradeDate, ticker, direction, filledQuantity, price, amountUsd, order.orderId()));
```
```java
    // LOC/MOC → 장마감 지정가(CLS), LIMIT → 정규장 지정가(DAY)
    private String resolveTimeInForce(Order.OrderType type) {
        return switch (type) {
            case LOC, MOC -> "CLS"; // MOC 미지원 → LIMIT+CLS로 장마감 경매 참여
            case LIMIT -> "DAY";
        };
    }

    // MOC 대체 주문: 최저가(0.01)로 장마감 경매에서 시장가처럼 체결되도록 유도
    private BigDecimal resolvePrice(Order.OrderType type, BigDecimal price) {
        return type == Order.OrderType.MOC ? new BigDecimal("0.01") : price;
    }
```

After:
```java
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.TossApiException;
```
(`import com.kista.trading.domain.model.Order;` 삭제 — 이 파일은 더 이상 trading의 `Order`를 참조하지 않는다)
```java
    public OrderResult place(OrderInstruction instruction, Account account) {
        // Toss는 MARKET 주문 미지원 — MOC도 LIMIT+CLS로 대체
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", instruction.ticker().name());              // 종목 코드 (예: SOXL)
        body.put("side", instruction.direction().name());             // BUY / SELL
        body.put("orderType", "LIMIT");                                // Toss 지원 타입: LIMIT만
        body.put("timeInForce", resolveTimeInForce(instruction.orderType())); // CLS(장마감) or DAY(정규장)
        body.put("quantity", instruction.quantity());
        body.put("price", resolvePrice(instruction.orderType(), instruction.price()));

        // Toss API 응답: {"result": {"orderId": "...", "clientOrderId": "..."}} — TossResult 언랩
        OrderResponse resp = TossResponseParser.unwrap(
                tossHttpClient.post(ORDER_PATH, account, body,
                        new ParameterizedTypeReference<TossResult<OrderResponse>>() {}));

        // orderId 없으면 비즈니스 실패 처리
        if (resp.orderId() == null) {
            throw new TossApiException("Toss 주문 실패: 응답에 orderId 없음", null);
        }

        // broker는 결과만 반환 — id=null, DB PLACED 기록은 trading 호출부(order.withPlaced())가 담당
        return new OrderResult(resp.orderId());
    }

    public void cancel(CancelInstruction instruction, Account account) {
        // POST /api/v1/orders/{externalOrderId}/cancel — DELETE 아님 (Toss 공식 스펙, cancelOrder)
        tossHttpClient.post(ORDER_PATH + "/" + instruction.externalOrderId() + "/cancel", account, Map.of(), Void.class);
    }
```
```java
        Direction direction = "BUY".equals(order.side()) ? Direction.BUY : Direction.SELL;

        return Optional.of(new Execution(tradeDate, ticker, direction, filledQuantity, price, amountUsd, order.orderId()));
```
```java
    // LOC/MOC → 장마감 지정가(CLS), LIMIT → 정규장 지정가(DAY)
    private String resolveTimeInForce(OrderType type) {
        return switch (type) {
            case LOC, MOC -> "CLS"; // MOC 미지원 → LIMIT+CLS로 장마감 경매 참여
            case LIMIT -> "DAY";
        };
    }

    // MOC 대체 주문: 최저가(0.01)로 장마감 경매에서 시장가처럼 체결되도록 유도
    private BigDecimal resolvePrice(OrderType type, BigDecimal price) {
        return type == OrderType.MOC ? new BigDecimal("0.01") : price;
    }
```
`OrderResponse`/`OrdersResponse`/`OrderItem`/`OrderExecutionItem` record 4개와 `fetchExecutions`/`getExecutions`/`buildOrderParams`/`parseFilledDate`/`parseAmountUsd` 메서드는 `Order`/`Direction` 타입을 직접 다루지 않으므로 무변경.

- [ ] **Step 3: `TossHoldingsApi.java` 수정**

Before:
```java
import com.kista.trading.domain.model.AccountBalance;
```
```java
    public AccountBalance getBalance(Account account, Ticker ticker) {
        TossResult<HoldingsResponse> wrapper = tossHttpClient.get(
                HOLDINGS_PATH, account, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<TossResult<HoldingsResponse>>() {});
        HoldingsResponse holdingsResponse = wrapper != null ? wrapper.result() : null;

        BigDecimal usdDeposit = getUsdBuyableAmount(account);

        if (holdingsResponse == null || holdingsResponse.items() == null) {
            return new AccountBalance(0, null, usdDeposit);
        }

        return holdingsResponse.items().stream()
                .filter(i -> ticker.name().equals(i.symbol()))
                .findFirst()
                .map(i -> {
                    int quantity = Integer.parseInt(i.quantity());
                    BigDecimal avg = quantity > 0 ? new BigDecimal(i.averagePurchasePrice()) : null;
                    return new AccountBalance(quantity, avg, usdDeposit);
                })
                .orElse(new AccountBalance(0, null, usdDeposit));
    }
```

After:
```java
import com.kista.broker.domain.model.BrokerBalance;
```
```java
    public BrokerBalance getBalance(Account account, Ticker ticker) {
        TossResult<HoldingsResponse> wrapper = tossHttpClient.get(
                HOLDINGS_PATH, account, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<TossResult<HoldingsResponse>>() {});
        HoldingsResponse holdingsResponse = wrapper != null ? wrapper.result() : null;

        BigDecimal usdDeposit = getUsdBuyableAmount(account);

        if (holdingsResponse == null || holdingsResponse.items() == null) {
            return new BrokerBalance(0, null, usdDeposit);
        }

        return holdingsResponse.items().stream()
                .filter(i -> ticker.name().equals(i.symbol()))
                .findFirst()
                .map(i -> {
                    int quantity = Integer.parseInt(i.quantity());
                    BigDecimal avg = quantity > 0 ? new BigDecimal(i.averagePurchasePrice()) : null;
                    return new BrokerBalance(quantity, avg, usdDeposit);
                })
                .orElse(new BrokerBalance(0, null, usdDeposit));
    }
```
나머지 메서드(`getMargin`/`getPresentBalance`/`getUsdBuyableAmount`/`getExchangeRate`/`getSellableQuantity`)는 `AccountBalance`/`Order` 타입과 무관하므로 무변경.

- [ ] **Step 4: `TossPriceApi.java` 수정 (PriceSnapshot 타입 치환 + DstInfo 복제)**

Before:
```java
import com.kista.broker.adapter.out.internal.PrevCloseCache;
import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.common.TimeZones;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.TossCandle;
import com.kista.broker.domain.model.toss.TossStockInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
```
```java
    private Optional<BigDecimal> fetchPrevCloseCached(String symbol) {
        DstInfo dstInfo = DstInfo.calculate();
        boolean regularSessionActive = dstInfo.isRegularSessionActive();
        Instant before = regularSessionActive
                ? dstInfo.lastSessionOpenInstant().minusMillis(1)  // 진행 중인 봉 배제
                : Instant.now();                                   // 이미 확정된 봉만 존재
        String bucket = regularSessionActive ? "ACTIVE" : "CLOSED";
        return prevCloseCache.getOrFetch(symbol, LocalDate.now(TimeZones.KST), bucket,
                () -> fetchPrevCloseUncached(symbol, before));
    }
```

After:
```java
import com.kista.broker.adapter.out.internal.PrevCloseCache;
import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.common.TimeZones;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.TossCandle;
import com.kista.broker.domain.model.toss.TossStockInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
```
(`import com.kista.trading.domain.model.DstInfo;` 삭제, `PriceSnapshot` import를 broker 소유로 교체, DST 계산에 필요한 `java.time.*` 4개 추가)
```java
    private Optional<BigDecimal> fetchPrevCloseCached(String symbol) {
        MarketSessionInfo session = resolveMarketSession();
        Instant before = session.regularSessionActive()
                ? session.lastSessionOpenInstant().minusMillis(1)  // 진행 중인 봉 배제
                : Instant.now();                                   // 이미 확정된 봉만 존재
        String bucket = session.regularSessionActive() ? "ACTIVE" : "CLOSED";
        return prevCloseCache.getOrFetch(symbol, LocalDate.now(TimeZones.KST), bucket,
                () -> fetchPrevCloseUncached(symbol, before));
    }

    // ── trading DstInfo.isRegularSessionActive()/lastSessionOpenInstant() 복제 ──────────────
    // 스케쥴러 오케스트레이션(waitUntilOrderTime 등)과 무관한 "정규장 진행 여부 + 마지막 개장 시각"
    // 순수 KST/DST 계산만 필요하므로 DstInfo 전체를 참조하지 않고 이 2개 계산만 좁게 복제한다
    // (common/ 승격 대상 아님 — trading 스케쥴링 도메인 클래스 전체를 끌어오는 것이 과함)

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // 미국 뉴욕 기준 DST 여부에 따른 개장/마감/프리마켓 시각(KST) — DstInfo와 동일 시각표
    private static LocalTime marketOpenTime(boolean isDst)     { return isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30); }
    private static LocalTime marketCloseTime(boolean isDst)    { return isDst ? LocalTime.of(5, 0)   : LocalTime.of(6, 0); }
    private static LocalTime premarketStartTime(boolean isDst) { return isDst ? LocalTime.of(17, 0)  : LocalTime.of(18, 0); }

    // 정규장 진행 여부 + 가장 최근 개장 시각을 한 번에 계산한 결과
    private record MarketSessionInfo(boolean regularSessionActive, Instant lastSessionOpenInstant) {}

    // 현재 KST 기준 정규장 진행 여부 + 가장 최근 개장 시각 계산
    private static MarketSessionInfo resolveMarketSession() {
        ZonedDateTime nowKst = ZonedDateTime.now(TimeZones.KST);
        boolean isDst = NY.getRules().isDaylightSavings(nowKst.toInstant());
        DayOfWeek day = nowKst.getDayOfWeek();
        LocalTime time = nowKst.toLocalTime();

        // 주말이거나 [장마감, 프리마켓시작) 구간이면 BLOCKED — 정규장 진행 중일 수 없음
        boolean blocked = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                || (!time.isBefore(marketCloseTime(isDst)) && time.isBefore(premarketStartTime(isDst)));
        // marketOpen~자정~marketClose 래핑 구간만 정규장 진행 중 (그 외 DIRECT 구간은 프리마켓)
        boolean regularSessionActive = !blocked
                && (!time.isBefore(marketOpenTime(isDst)) || time.isBefore(marketCloseTime(isDst)));

        // 가장 최근 개장 시각 — 자정~개장 전(00:00~marketOpen)이면 전날 저녁 개장을 가리켜야 함(날짜 롤백)
        LocalDate sessionDate = time.isBefore(marketOpenTime(isDst))
                ? nowKst.toLocalDate().minusDays(1)
                : nowKst.toLocalDate();
        Instant lastSessionOpenInstant = sessionDate.atTime(marketOpenTime(isDst)).atZone(TimeZones.KST).toInstant();

        return new MarketSessionInfo(regularSessionActive, lastSessionOpenInstant);
    }
```
나머지 메서드(`getPrices`/`getPrice`/`getPriceSnapshot`/`getPriceSnapshots`/`getPrevClose`/`getPrevCloses`/`getClosingPrice`/`fetchPrevCloseUncached`/`getStockInfo` 등)는 `PriceSnapshot` import가 broker 타입으로 바뀐 것 외엔 본문 무변경.

**테스트 시드 보존 확인**: `fetchPrevCloseUncached(String symbol, Instant before)`는 package-private 그대로 유지되고, `DstInfo`/`resolveMarketSession()`을 전혀 호출하지 않는다(`before`를 인자로 받을 뿐). `TossPriceApiTest`의 관련 테스트는 이 메서드를 직접 호출해 `Instant.now()`를 주입하는 방식으로 시각 제어를 우회하고 있으므로, `resolveMarketSession()`으로 바뀌어도 이 테스트 시드는 완전히 그대로 유지된다 — 수정 불필요.

- [ ] **Step 5: `CommonMarketPriceFeed.java` 수정**

Before:
```java
import com.kista.trading.domain.model.PriceSnapshot;
```
After:
```java
import com.kista.broker.domain.model.PriceSnapshot;
```
인터페이스 메서드 시그니처(`getPriceSnapshot(Ticker)`/`getPriceSnapshots(List<Ticker>)`) 자체는 무변경 — import만 교체.

- [ ] **Step 6: 테스트 갱신 — `TossOrderApiTest.java`**

변경 이유: `place`/`cancel`이 `Order` 대신 `OrderInstruction`/`CancelInstruction`/`OrderResult`를 주고받고, `Execution.direction()`이 `Direction` 타입을 반환하도록 바뀌므로 현재 코드는 컴파일되지 않는다.

Before:
```java
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Order.OrderDirection;
import com.kista.domain.model.strategy.Strategy.Ticker;
```
```java
    @Test
    @DisplayName("LOC 주문 → orderType=LIMIT, timeInForce=CLS, PLACED 상태 반환")
    void place_loc_mapsToLimitCls() {
        Order order = locBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id"));

        Order placed = tossOrderApi.place(order, ACCOUNT);

        assertThat(placed.externalOrderId()).isEqualTo("toss-order-id");
        assertThat(placed.status()).isEqualTo(Order.OrderStatus.PLACED);
        ...
```
```java
    @Test
    @DisplayName("MOC 주문 → timeInForce=CLS, price=0.01 (장마감 LIMIT 대체)")
    void place_moc_usesLimitClsWithMinPrice() {
        Order order = mocSellOrder();
        ...
        tossOrderApi.place(order, ACCOUNT);
```
```java
    @Test
    @DisplayName("LIMIT 주문 → timeInForce=DAY")
    void place_limit_mapsToLimitDay() {
        Order order = limitBuyOrder();
        ...
        tossOrderApi.place(order, ACCOUNT);
```
```java
    @Test
    @DisplayName("응답 orderId null → TossApiException")
    void place_nullOrderId_throwsTossApiException() {
        Order order = locBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossOrderApi.OrderResponse(null, null)));

        assertThatThrownBy(() -> tossOrderApi.place(order, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소: POST /api/v1/orders/{externalOrderId}/cancel")
    void cancel_callsPostCancelWithOrderId() {
        Order order = new Order(UUID.randomUUID(), null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "toss-oid-123", null, null);

        tossOrderApi.cancel(order, ACCOUNT);

        verify(tossHttpClient).post(eq("/api/v1/orders/toss-oid-123/cancel"), any(), any(), eq(Void.class));
    }

    @Test
    @DisplayName("취소 실패(500)는 그대로 전파된다")
    void cancel_serverError_rethrows() {
        Order order = new Order(UUID.randomUUID(), null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "toss-oid-500", null, null);
        doThrow(new TossApiException("Toss API 요청 실패: 500", new RuntimeException("boom")))
            .when(tossHttpClient).post(anyString(), any(), any(), eq(Void.class));

        assertThatThrownBy(() -> tossOrderApi.cancel(order, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소 실패(404)도 이미 체결/만료로 추정하지 않고 그대로 전파된다")
    void cancel_notFound_rethrows() {
        Order order = new Order(UUID.randomUUID(), null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "toss-oid-404", null, null);
        doThrow(new TossApiException("Toss API 오류: 404 NOT_FOUND", new RuntimeException("not found")))
            .when(tossHttpClient).post(anyString(), any(), any(), eq(Void.class));

        assertThatThrownBy(() -> tossOrderApi.cancel(order, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }
```
```java
        assertThat(e.direction()).isEqualTo(OrderDirection.BUY);
```
(다른 곳: `getExecutions_open_partialFilled_included`의 `OrderDirection.SELL`도 동일 패턴)
```java
    private Order locBuyOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 2, new BigDecimal("25.50"),
            Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order mocSellOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.MOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, BigDecimal.ZERO,
            Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order limitBuyOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLANNED, null, null, null);
    }
```

After:
```java
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.domain.model.strategy.Strategy.Ticker;
```
(`Order`/`Order.OrderDirection` import 삭제 — 파일 전체에서 trading `Order` 참조가 사라짐)
```java
    @Test
    @DisplayName("LOC 주문 → orderType=LIMIT, timeInForce=CLS, externalOrderId 반환")
    void place_loc_mapsToLimitCls() {
        OrderInstruction instruction = locBuyInstruction();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id"));

        OrderResult result = tossOrderApi.place(instruction, ACCOUNT);

        assertThat(result.externalOrderId()).isEqualTo("toss-order-id");
        ...
```
```java
    @Test
    @DisplayName("MOC 주문 → timeInForce=CLS, price=0.01 (장마감 LIMIT 대체)")
    void place_moc_usesLimitClsWithMinPrice() {
        OrderInstruction instruction = mocSellInstruction();
        ...
        tossOrderApi.place(instruction, ACCOUNT);
```
```java
    @Test
    @DisplayName("LIMIT 주문 → timeInForce=DAY")
    void place_limit_mapsToLimitDay() {
        OrderInstruction instruction = limitBuyInstruction();
        ...
        tossOrderApi.place(instruction, ACCOUNT);
```
```java
    @Test
    @DisplayName("응답 orderId null → TossApiException")
    void place_nullOrderId_throwsTossApiException() {
        OrderInstruction instruction = locBuyInstruction();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossOrderApi.OrderResponse(null, null)));

        assertThatThrownBy(() -> tossOrderApi.place(instruction, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소: POST /api/v1/orders/{externalOrderId}/cancel")
    void cancel_callsPostCancelWithOrderId() {
        CancelInstruction instruction = new CancelInstruction(Ticker.SOXL, "toss-oid-123");

        tossOrderApi.cancel(instruction, ACCOUNT);

        verify(tossHttpClient).post(eq("/api/v1/orders/toss-oid-123/cancel"), any(), any(), eq(Void.class));
    }

    @Test
    @DisplayName("취소 실패(500)는 그대로 전파된다")
    void cancel_serverError_rethrows() {
        CancelInstruction instruction = new CancelInstruction(Ticker.SOXL, "toss-oid-500");
        doThrow(new TossApiException("Toss API 요청 실패: 500", new RuntimeException("boom")))
            .when(tossHttpClient).post(anyString(), any(), any(), eq(Void.class));

        assertThatThrownBy(() -> tossOrderApi.cancel(instruction, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소 실패(404)도 이미 체결/만료로 추정하지 않고 그대로 전파된다")
    void cancel_notFound_rethrows() {
        CancelInstruction instruction = new CancelInstruction(Ticker.SOXL, "toss-oid-404");
        doThrow(new TossApiException("Toss API 오류: 404 NOT_FOUND", new RuntimeException("not found")))
            .when(tossHttpClient).post(anyString(), any(), any(), eq(Void.class));

        assertThatThrownBy(() -> tossOrderApi.cancel(instruction, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }
```
```java
        assertThat(e.direction()).isEqualTo(Direction.BUY);
```
(`getExecutions_open_partialFilled_included`도 `Direction.SELL`로 동일 교체)
```java
    private OrderInstruction locBuyInstruction() {
        return new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.LOC, 2, new BigDecimal("25.50"));
    }

    private OrderInstruction mocSellInstruction() {
        return new OrderInstruction(Ticker.SOXL, Direction.SELL, OrderType.MOC, 1, BigDecimal.ZERO);
    }

    private OrderInstruction limitBuyInstruction() {
        return new OrderInstruction(Ticker.SOXL, Direction.BUY, OrderType.LIMIT, 1, new BigDecimal("25.00"));
    }
```
`UUID` import는 `Order` 생성에만 쓰였다면 사용처가 없어지므로 삭제(다른 용도로 안 쓰이는지 `grep -n "UUID" src/test/java/com/kista/broker/adapter/out/toss/TossOrderApiTest.java`로 확인 후 판단). `LocalDate`는 `getExecutions` 테스트들이 여전히 쓰므로 유지.

- [ ] **Step 7: 테스트 갱신 — `TossPriceApiTest.java`**

Before:
```java
import com.kista.trading.domain.model.PriceSnapshot;
```
After:
```java
import com.kista.broker.domain.model.PriceSnapshot;
```
그 외 전 테스트 메서드는 `snapshot.current()`/`snapshot.prevClose()` 등 accessor만 사용하며 broker `PriceSnapshot`도 동일한 필드명을 가지므로 본문 변경 없음.

- [ ] **Step 8: 테스트 갱신 — `TossHoldingsApiTest.java`**

Before:
```java
import com.kista.trading.domain.model.AccountBalance;
```
```java
        AccountBalance balance = tossHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);
```
(`getBalance_holdingFound_returnsBalance`, `getBalance_noHolding_returnsZeroBalance`, `getBalance_nullResponse_returnsZeroBalance` 3곳)

After:
```java
import com.kista.broker.domain.model.BrokerBalance;
```
```java
        BrokerBalance balance = tossHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);
```
(3곳 모두 동일 치환) — `balance.holdings()`/`balance.avgPrice()`/`balance.usdDeposit()` accessor는 필드명이 동일해 그대로 유지. `getMargin`/`getExchangeRate`/`getPresentBalance` 관련 테스트는 무변경.

- [ ] **Step 9: `TossBrokerAdapterTest.java` — 변경 불필요 확인**

이 테스트는 `assertThat(adapter).isInstanceOf(BrokerPricePort.class)` 형태로 포트 인터페이스 구현 여부만 검증하고, `place`/`cancel`/`getPriceSnapshot`/`getLiveBalance`를 실제로 호출하거나 반환 타입을 assert하지 않는다 — 수정 불필요함을 확인만 한다.

- [ ] **Step 10: 컴파일·테스트 검증**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
./gradlew test --tests 'com.kista.broker.adapter.out.toss.*'
```
주의: 이 시점에 `MockBrokerAdapter`(Task 6 소관)가 아직 broker 타입으로 안 바뀌었다면 `CommonMarketPriceFeed` 타입 변경의 여파로 `MockBrokerAdapter.java`가 컴파일 실패할 수 있다 — Task 6을 이어서 처리하면 해소된다.

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/kista/broker/adapter/out/toss/TossBrokerAdapter.java \
        src/main/java/com/kista/broker/adapter/out/toss/TossOrderApi.java \
        src/main/java/com/kista/broker/adapter/out/toss/TossHoldingsApi.java \
        src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java \
        src/main/java/com/kista/adapter/out/marketdata/CommonMarketPriceFeed.java \
        src/test/java/com/kista/broker/adapter/out/toss/TossOrderApiTest.java \
        src/test/java/com/kista/broker/adapter/out/toss/TossPriceApiTest.java \
        src/test/java/com/kista/broker/adapter/out/toss/TossHoldingsApiTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): Toss 어댑터를 broker 소유 타입 기반으로 갱신 + DstInfo 부분 복제

TossBrokerAdapter/TossOrderApi/TossHoldingsApi/TossPriceApi가 Task 1의 broker
소유 타입(Direction/OrderType/PriceSnapshot/BrokerBalance/OrderInstruction/
OrderResult/CancelInstruction)을 쓰도록 갱신 — KIS 어댑터(Task 2)와 동일 패턴.
TossPriceApi의 DstInfo.calculate() 의존은 정규장 진행 여부·최근 개장 시각
계산만 private으로 복제해 제거(스케쥴러 오케스트레이션과 무관한 별개 관심사,
common/ 승격은 하지 않음). CommonMarketPriceFeed(TossPriceApi가 구현하는
계좌무관 시세 인터페이스)도 PriceSnapshot import 교체로 함께 갱신. 대응
테스트 3개 갱신. 로직 변경 없음(DstInfo 복제 부분 제외 순수 타입 치환).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

---

### Task 5: trading/legacy 호출부 매핑 추가

**Files:**
- Modify `src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java`
- Modify `src/main/java/com/kista/trading/application/service/OrderCancelService.java`
- Modify `src/main/java/com/kista/trading/application/service/TradingReporter.java`
- Modify `src/main/java/com/kista/application/service/admin/AdminReorderService.java`
- Modify `src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java`
- Modify `src/main/java/com/kista/trading/application/service/ManualTradingService.java`
- `src/main/java/com/kista/trading/application/service/PreviewDepositCache.java` — **프로덕션 코드 변경 불필요** (아래 Step 7 근거, 테스트만 갱신)
- `src/main/java/com/kista/application/service/strategy/StrategyService.java` — **변경 완전 불필요** (아래 Step 8 근거 — `LiveBalancePort`를 애초에 호출하지 않음)
- Modify `src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java`
- `src/main/java/com/kista/adapter/out/marketdata/CommonMarketPriceFeed.java` — **Task 3 Step 5에서 이미 처리됨, 여기선 재작업 없음**
- Test: `TradingOrderExecutorTest.java`, `OrderCancelServiceTest.java`, `TradingReporterTest.java`, `AdminReorderServiceTest.java`, `TradingOrderBudgetAllocatorTest.java`, `ManualTradingServiceTest.java`, `PreviewDepositCacheTest.java`

**Interfaces:**
- Consumes: Task 1의 broker 소유 타입 7종 + 변경된 포트 시그니처(Task 1), Task 4의 `Execution.direction()`이 `Direction` 반환
- Produces: 이 태스크가 끝나면 trading→broker 방향의 모든 호출부가 broker 신규 타입으로 정합화됨 — Task 8의 최종 컴파일 확인 대상

- [ ] **Step 1: `TradingOrderExecutor.java` — `BrokerOrderCorrectionPort.place` 호출부**

Before:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.VrPosition;
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
```
```java
    private List<Order> placeEach(List<Order> orders, Account account) {
        List<Order> placed = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[{}] 인터럽트 감지 — 남은 주문 {}건 접수 중단", account.nickname(), orders.size() - i);
                break;
            }
            Order p = orders.get(i);
            Order placedOrder;
            try {
                placedOrder = registry.require(account, BrokerOrderCorrectionPort.class).place(p, account);
            } catch (Exception e) {
                log.warn("[{}] {} {} 주문 접수 실패: {}", account.nickname(), p.direction(), p.ticker(), e.getMessage());
                notifyPort.notifyError(e);
                orderPort.markFailed(p.id()); // 접수 실패 → FAILED
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("[{}] 인터럽트 감지 — 남은 주문 접수 중단", account.nickname());
                    break;
                }
                continue;
            }
            try {
                markPlacedWithRetry(p.id(), placedOrder.externalOrderId());
                placed.add(p.withPlaced(placedOrder.externalOrderId()));
            } catch (Exception e) {
                log.error("[{}] {} {} 증권사 접수 완료됐으나 DB PLACED 기록 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                        account.nickname(), p.direction(), p.ticker(), placedOrder.externalOrderId(), e.getMessage());
                notifyPort.notifyError(new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + placedOrder.externalOrderId(), e));
            }
        }
        return placed;
    }
```

After:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Direction;
import com.kista.trading.domain.model.Order;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.VrPosition;
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
```
```java
    private List<Order> placeEach(List<Order> orders, Account account) {
        List<Order> placed = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[{}] 인터럽트 감지 — 남은 주문 {}건 접수 중단", account.nickname(), orders.size() - i);
                break;
            }
            Order p = orders.get(i);
            OrderInstruction instruction = new OrderInstruction(p.ticker(), toDirection(p.direction()),
                    toOrderType(p.orderType()), p.quantity(), p.price());
            OrderResult result;
            try {
                result = registry.require(account, BrokerOrderCorrectionPort.class).place(instruction, account);
            } catch (Exception e) {
                log.warn("[{}] {} {} 주문 접수 실패: {}", account.nickname(), p.direction(), p.ticker(), e.getMessage());
                notifyPort.notifyError(e);
                orderPort.markFailed(p.id()); // 접수 실패 → FAILED
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("[{}] 인터럽트 감지 — 남은 주문 접수 중단", account.nickname());
                    break;
                }
                continue;
            }
            try {
                markPlacedWithRetry(p.id(), result.externalOrderId());
                placed.add(p.withPlaced(result.externalOrderId()));
            } catch (Exception e) {
                log.error("[{}] {} {} 증권사 접수 완료됐으나 DB PLACED 기록 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                        account.nickname(), p.direction(), p.ticker(), result.externalOrderId(), e.getMessage());
                notifyPort.notifyError(new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + result.externalOrderId(), e));
            }
        }
        return placed;
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }

    // trading Order.OrderType → broker OrderType (값 1:1 대응, enum 이름 동일)
    private static OrderType toOrderType(Order.OrderType orderType) {
        return switch (orderType) {
            case LOC -> OrderType.LOC;
            case MOC -> OrderType.MOC;
            case LIMIT -> OrderType.LIMIT;
        };
    }
```

테스트 갱신 — `TradingOrderExecutorTest.java`:
```java
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
```
```java
private OrderResult brokerResult(String externalOrderId) {
    return new OrderResult(externalOrderId);
}

// 프로덕션 매핑과 동일한 규칙으로 기대 OrderInstruction 구성 — place() stub 매칭용
private static OrderInstruction instructionOf(Order order) {
    Direction direction = order.direction() == Order.OrderDirection.BUY ? Direction.BUY : Direction.SELL;
    OrderType orderType = switch (order.orderType()) {
        case LOC -> OrderType.LOC;
        case MOC -> OrderType.MOC;
        case LIMIT -> OrderType.LIMIT;
    };
    return new OrderInstruction(order.ticker(), direction, orderType, order.quantity(), order.price());
}
```
단일 주문 테스트(대부분): `when(brokerPort.place(plannedOrder, ACCOUNT)).thenReturn(kisResponse("KIS-001"));` → `when(brokerPort.place(any(OrderInstruction.class), eq(ACCOUNT))).thenReturn(brokerResult("KIS-001"));`

복수 주문 구분이 필요한 `placeOrders_multiplePlannedOrders_placesAllInOrder`(order1=BUY 10@50.00, order2=SELL 5@60.00)만 정확 매칭 필요:
```java
when(brokerPort.place(eq(instructionOf(order1)), eq(ACCOUNT))).thenReturn(brokerResult("KIS-101"));
when(brokerPort.place(eq(instructionOf(order2)), eq(ACCOUNT))).thenReturn(brokerResult("KIS-102"));
```
`assertThat(result.getFirst().status()).isEqualTo(Order.OrderStatus.PLACED);` 등 `Order` 관련 단언은 `p.withPlaced(...)`로 여전히 프로덕션에서 생성되므로 그대로 유지.

- [ ] **Step 2: `OrderCancelService.java` — `BrokerOrderCorrectionPort.cancel` 호출부 (2곳)**

Before:
```java
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
```
```java
        for (Order order : placedOrders) {
            try {
                registry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
                stateWriter.markCancelled(order.id());
```
```java
        try {
            registry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
        } catch (Exception e) {
```

After:
```java
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
```
```java
        for (Order order : placedOrders) {
            try {
                registry.require(account, BrokerOrderCorrectionPort.class)
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account);
                stateWriter.markCancelled(order.id());
```
```java
        try {
            registry.require(account, BrokerOrderCorrectionPort.class)
                    .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account);
        } catch (Exception e) {
```

테스트 갱신 — `OrderCancelServiceTest.java`:
```java
import com.kista.broker.domain.model.CancelInstruction;
```
```java
private static CancelInstruction cancelOf(Order order) {
    return new CancelInstruction(order.ticker(), order.externalOrderId());
}
```
- `verify(brokerPort, times(2)).cancel(any(), eq(ownedAccount));` — 변경 불필요(여전히 2-arg, `any()` 유지)
- `doNothing().when(brokerPort).cancel(eq(order1), any());` / `doThrow(...).when(brokerPort).cancel(eq(order2), any());` → `eq(cancelOf(order1))` / `eq(cancelOf(order2))`로 교체 (`cancelByCycle_partialFailure`, `cancelByCycle_alreadyCanceledConflict_absorbedAsSuccess`)
- `verify(brokerPort).cancel(order, ownedAccount);` → `verify(brokerPort).cancel(cancelOf(order), ownedAccount);` (`cancelOrder_success`)
- `doThrow(...).when(brokerPort).cancel(order, ownedAccount);` → `doThrow(...).when(brokerPort).cancel(cancelOf(order), ownedAccount);` (`cancelOrder_alreadyCanceledConflict_absorbedAsSuccess`, `cancelOrder_otherBrokerFailure_propagates`)
- `inOrder.verify(brokerPort).cancel(order, ownedAccount);` → `inOrder.verify(brokerPort).cancel(cancelOf(order), ownedAccount);` (`cancelOrder_marksCancelledOnlyAfterBrokerCancelSucceeds`)

- [ ] **Step 3: `TradingReporter.java` — `BrokerOrderCorrectionPort.cancel` 호출부 + `Execution.direction()` 타입 파급**

이 파일은 `Execution`을 실제로 소비하며(`getExecutions` 결과), `BrokerOrderCorrectionPort.cancel`도 호출한다(`cancelUnresolvedOrders`). 추가로 Task 4가 `Execution.direction()`을 broker `Direction`으로 바꾸면 `buildReport()`의 `e.direction() == BUY`(trading `Order.OrderDirection.BUY`의 static import) 비교가 타입 불일치로 컴파일이 깨지므로 함께 고친다.

Before:
```java
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
import com.kista.broker.domain.port.out.ExecutionPort;
```
```java
import static com.kista.trading.domain.model.Order.OrderDirection.BUY;
import static com.kista.trading.domain.model.Order.OrderDirection.SELL;
```
```java
    private void cancelUnresolvedOrders(List<Order> mainOrders, Account account) {
        if (account.broker() != Account.Broker.TOSS) return;
        for (Order order : mainOrders) {
            if (order.status() != Order.OrderStatus.PLACED || order.externalOrderId() == null) continue;
            try {
                registry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
            } catch (Exception e) {
```
```java
    private TradingReport buildReport(LocalDate today, Strategy.Type strategyType, Strategy.Ticker ticker, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, strategyType, ticker, totalBought, totalSold);
    }
```

After:
```java
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
import com.kista.broker.domain.port.out.ExecutionPort;
```
(static import 2줄 삭제)
```java
    private void cancelUnresolvedOrders(List<Order> mainOrders, Account account) {
        if (account.broker() != Account.Broker.TOSS) return;
        for (Order order : mainOrders) {
            if (order.status() != Order.OrderStatus.PLACED || order.externalOrderId() == null) continue;
            try {
                registry.require(account, BrokerOrderCorrectionPort.class)
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account);
            } catch (Exception e) {
```
```java
    private TradingReport buildReport(LocalDate today, Strategy.Type strategyType, Strategy.Ticker ticker, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == Direction.BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == Direction.SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, strategyType, ticker, totalBought, totalSold);
    }
```

테스트 갱신 — `TradingReporterTest.java`:
```java
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
```
```java
private static Execution buyExecution(String externalOrderId, int quantity, String price) {
    BigDecimal p = new BigDecimal(price);
    return new Execution(TODAY, Ticker.SOXL, Direction.BUY,
            quantity, p, p.multiply(BigDecimal.valueOf(quantity)), externalOrderId);
}
```
(`Order.OrderDirection.BUY` → `Direction.BUY`, `SELL`도 동일)

4곳의 cancel 검증(모두 `order` 단일 인스턴스, `externalOrderId="E1"` 고정)을 각각 교체:
- `inOrder.verify(brokerOrderPort).cancel(order, TOSS_ACCOUNT);` → `inOrder.verify(brokerOrderPort).cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), TOSS_ACCOUNT);`
- `doThrow(...).when(brokerOrderPort).cancel(order, TOSS_ACCOUNT);` (2곳) → `.cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), TOSS_ACCOUNT);`
- `verify(brokerOrderPort, never()).cancel(any(), any());` — 변경 불필요

- [ ] **Step 4: `AdminReorderService.java` — `place`/`cancel` 호출부 (2곳)**

Before:
```java
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
```
```java
    private void cancelIfNeeded(Order order, Account account) {
        switch (order.status()) {
            case PLANNED -> orderPort.markCancelled(order.id());
            case PLACED -> {
                brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
                orderPort.markCancelled(order.id());
            }
            default -> {} // FILLED/PARTIALLY_FILLED/FAILED/CANCELLED: 이미 종료 상태, no-op
        }
    }

    private PlacementResult placeOrSave(Order newOrder, Account account, Order.OrderTiming timing) {
        if (timing == Order.OrderTiming.IMMEDIATE) {
            BrokerOrderCorrectionPort broker = brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class);
            try {
                Order placed = broker.place(newOrder, account);
                orderPort.saveAll(List.of(placed));
                return new PlacementResult(Order.OrderStatus.PLACED, placed.externalOrderId());
            } catch (Exception e) {
                log.warn("재주문 즉시 접수 실패 — FAILED 기록: error={}", e.getMessage());
                orderPort.saveAll(List.of(newOrder.withFailed()));
                return new PlacementResult(Order.OrderStatus.FAILED, null);
            }
        }
        orderPort.saveAll(List.of(newOrder));
        return new PlacementResult(Order.OrderStatus.PLANNED, null);
    }
```

After:
```java
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.broker.domain.port.out.BrokerOrderCorrectionPort;
```
```java
    private void cancelIfNeeded(Order order, Account account) {
        switch (order.status()) {
            case PLANNED -> orderPort.markCancelled(order.id());
            case PLACED -> {
                brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class)
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account);
                orderPort.markCancelled(order.id());
            }
            default -> {} // FILLED/PARTIALLY_FILLED/FAILED/CANCELLED: 이미 종료 상태, no-op
        }
    }

    private PlacementResult placeOrSave(Order newOrder, Account account, Order.OrderTiming timing) {
        if (timing == Order.OrderTiming.IMMEDIATE) {
            BrokerOrderCorrectionPort broker = brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class);
            try {
                OrderInstruction instruction = new OrderInstruction(newOrder.ticker(), toDirection(newOrder.direction()),
                        toOrderType(newOrder.orderType()), newOrder.quantity(), newOrder.price());
                OrderResult result = broker.place(instruction, account);
                Order placed = newOrder.withPlaced(result.externalOrderId());
                orderPort.saveAll(List.of(placed));
                return new PlacementResult(Order.OrderStatus.PLACED, placed.externalOrderId());
            } catch (Exception e) {
                log.warn("재주문 즉시 접수 실패 — FAILED 기록: error={}", e.getMessage());
                orderPort.saveAll(List.of(newOrder.withFailed()));
                return new PlacementResult(Order.OrderStatus.FAILED, null);
            }
        }
        orderPort.saveAll(List.of(newOrder));
        return new PlacementResult(Order.OrderStatus.PLANNED, null);
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }

    // trading Order.OrderType → broker OrderType (값 1:1 대응, enum 이름 동일)
    private static OrderType toOrderType(Order.OrderType orderType) {
        return switch (orderType) {
            case LOC -> OrderType.LOC;
            case MOC -> OrderType.MOC;
            case LIMIT -> OrderType.LIMIT;
        };
    }
```

테스트 갱신 — `AdminReorderServiceTest.java`:
```java
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.OrderResult;
```
- `verify(brokerOrderCorrectionPort).cancel(placedOrder(), account());` → `verify(brokerOrderCorrectionPort).cancel(new CancelInstruction(placedOrder().ticker(), placedOrder().externalOrderId()), account());` (`reorder_fromPlaced_cancelsBrokerThenSavesPlanned`)
- `when(brokerOrderCorrectionPort.place(any(), any())).thenAnswer(inv -> ((Order) inv.getArgument(0)).withPlaced("NEW-EXT-1"));` → `when(brokerOrderCorrectionPort.place(any(), any())).thenReturn(new OrderResult("NEW-EXT-1"));` (`reorder_immediate_success_savesPlaced` — 첫 인자가 이제 `OrderInstruction`이라 캐스팅 불가하므로 단순 `thenReturn`으로 교체, 테스트 의도는 그대로 검증됨)
- `when(brokerOrderCorrectionPort.place(any(), any())).thenThrow(new RuntimeException("증권사 오류"));` — 변경 불필요

- [ ] **Step 5: `TradingOrderBudgetAllocator.java` — `LiveBalancePort.getLiveBalance` 호출부**

Before:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.broker.domain.port.out.LiveBalancePort;
```
```java
            AccountBalance liveBalance = null;
            if (!buyCandidates.isEmpty()) {
                Candidate probe = buyCandidates.stream().sorted(buyPriorityComparator()).findFirst().orElseThrow();
                liveBalance = registry.require(account, LiveBalancePort.class)
                        .getLiveBalance(account, probe.ctx().strategy().ticker());
            }
```

After:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.trading.domain.model.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.broker.domain.port.out.LiveBalancePort;
```
```java
            AccountBalance liveBalance = null;
            if (!buyCandidates.isEmpty()) {
                Candidate probe = buyCandidates.stream().sorted(buyPriorityComparator()).findFirst().orElseThrow();
                BrokerBalance bb = registry.require(account, LiveBalancePort.class)
                        .getLiveBalance(account, probe.ctx().strategy().ticker());
                liveBalance = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
            }
```
`AccountQuote(AccountBalance liveBalance, ...)` record와 나머지 로직은 여전히 trading `AccountBalance`를 다루므로 무변경.

테스트 갱신 — `TradingOrderBudgetAllocatorTest.java`:
```java
import com.kista.broker.domain.model.BrokerBalance;
```
6곳의 `when(liveBalancePort.getLiveBalance(...)).thenReturn(new AccountBalance(...));`를 전부 `new BrokerBalance(...)`로 치환(필드 순서·값은 동일하게 유지). 예외 스텁(`.thenThrow(brokerFailure)`)은 타입 무관, 변경 불필요.

- [ ] **Step 6: `ManualTradingService.java` — `LiveBalancePort.getLiveBalance` 호출부**

Before:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
...
import com.kista.broker.domain.port.out.LiveBalancePort;
```
```java
    private AccountBalance fetchLiveBalanceOrThrow(Account account, Strategy strategy) {
        try {
            AccountBalance lb = registry.require(account, LiveBalancePort.class).getLiveBalance(account, strategy.ticker());
            log.info("live 잔고 조회: [{}] {} holdings={}주, usdDeposit=${}",
                    account.nickname(), strategy.ticker().name(), lb.holdings(), lb.usdDeposit());
            return lb;
        } catch (Exception e) {
```

After:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerBalance;
...
import com.kista.broker.domain.port.out.LiveBalancePort;
```
```java
    private AccountBalance fetchLiveBalanceOrThrow(Account account, Strategy strategy) {
        try {
            BrokerBalance bb = registry.require(account, LiveBalancePort.class).getLiveBalance(account, strategy.ticker());
            AccountBalance lb = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
            log.info("live 잔고 조회: [{}] {} holdings={}주, usdDeposit=${}",
                    account.nickname(), strategy.ticker().name(), lb.holdings(), lb.usdDeposit());
            return lb;
        } catch (Exception e) {
```
파일 상단에 이미 `import com.kista.domain.model.strategy.*; import com.kista.trading.domain.model.*;` 와일드카드가 있어 `AccountBalance`는 그대로 잡힌다 — `BrokerBalance`만 새로 추가.

테스트 갱신 — `ManualTradingServiceTest.java`:
```java
import com.kista.broker.domain.model.BrokerBalance;
```
4곳의 `when(liveBalancePort.getLiveBalance(...)).thenReturn(new AccountBalance(...));`를 `new BrokerBalance(...)`로 치환.

- [ ] **Step 7: `PreviewDepositCache.java` — 프로덕션 코드 변경 불필요 확인, 테스트만 갱신**

`registry.require(account, LiveBalancePort.class).getLiveBalance(account, probeTicker).usdDeposit()` 체이닝은 반환값을 지역 변수·필드에 `AccountBalance` 타입으로 저장하지 않고 즉시 `.usdDeposit()`만 호출한다. `BrokerBalance`도 동일한 `usdDeposit()` accessor를 가지므로 `LiveBalancePort.getLiveBalance`의 반환 타입이 바뀌어도 이 파일은 한 글자도 바꿀 필요가 없다(이 파일은 애초에 `AccountBalance`를 import조차 하지 않는다).

테스트 갱신 — `PreviewDepositCacheTest.java`:
```java
import com.kista.broker.domain.model.BrokerBalance;
```
(기존 `import com.kista.trading.domain.model.AccountBalance;`는 다른 용도가 없으면 삭제)
4곳의 `new AccountBalance(0, null, new BigDecimal("1000.00"))`을 `new BrokerBalance(0, null, new BigDecimal("1000.00"))`로 치환.

- [ ] **Step 8: `StrategyService.java` — 변경 완전 불필요 확인**

```bash
grep -n "getLiveBalance" src/main/java/com/kista/application/service/strategy/StrategyService.java
```
결과 없음을 확인한다. 이 파일이 실제 호출하는 broker 포트는 `MarginPort.getUsdBuyableAmount`(반환 `BigDecimal`, 무관)와 `BrokerPricePort.getPrevClose`(반환 `BigDecimal`, `getPriceSnapshot`/`getPriceSnapshots`만 바뀌므로 무관) 뿐이다 — 이 태스크에서 손댈 필요 없음(필드 주석에 "LiveBalancePort 경유"라는 stale 문구가 있으나 실제 호출이 없으므로 문서 드리프트일 뿐, 이번 스코프 밖이라 임의 수정하지 않고 발견만 기록).

- [ ] **Step 9: `TradingPriceFetcher.java` — `BrokerPricePort.getPriceSnapshot(s)` 호출부**

Before:
```java
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.broker.domain.port.out.BrokerPricePort;
```
```java
    Map<Ticker, PriceSnapshot> fetchPriceSnapshots(List<Ticker> tickers, Account account) {
        return fetchWithFallback(tickers, account, "스냅샷",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshots(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshot(t, acc));
    }
```

After (import는 유지 — trading `PriceSnapshot`이 이 메서드의 공개 반환 타입이므로 그대로, broker의 `PriceSnapshot`은 FQN으로만 참조):
```java
    Map<Ticker, PriceSnapshot> fetchPriceSnapshots(List<Ticker> tickers, Account account) {
        Map<Ticker, com.kista.broker.domain.model.PriceSnapshot> brokerSnapshots = fetchWithFallback(tickers, account, "스냅샷",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshots(t, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getPriceSnapshot(t, acc));
        // broker 소유 PriceSnapshot(2필드 복제 타입) → trading 소유 PriceSnapshot 매핑 — 필드 구성 동일, 타입만 다름
        Map<Ticker, PriceSnapshot> result = new HashMap<>();
        brokerSnapshots.forEach((ticker, snap) -> result.put(ticker, new PriceSnapshot(snap.current(), snap.prevClose())));
        return result;
    }
```
`fetchPrices`/`fetchPrevCloses`/`fetchClosingPrices`(반환 `BigDecimal`)와 `fetchWithFallback` 제네릭 골격은 무변경. `TradingPriceFetcherTest.java`는 존재하지 않는다(`find src/test -iname "TradingPriceFetcherTest.java"` 결과 없음) — 테스트 갱신 불필요.

- [ ] **Step 10: 컴파일·테스트 검증**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
./gradlew test --tests 'com.kista.trading.application.service.*' \
  --tests 'com.kista.application.service.admin.AdminReorderServiceTest'
```

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java \
        src/main/java/com/kista/trading/application/service/OrderCancelService.java \
        src/main/java/com/kista/trading/application/service/TradingReporter.java \
        src/main/java/com/kista/application/service/admin/AdminReorderService.java \
        src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java \
        src/main/java/com/kista/trading/application/service/ManualTradingService.java \
        src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java \
        src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java \
        src/test/java/com/kista/trading/application/service/OrderCancelServiceTest.java \
        src/test/java/com/kista/trading/application/service/TradingReporterTest.java \
        src/test/java/com/kista/application/service/admin/AdminReorderServiceTest.java \
        src/test/java/com/kista/trading/application/service/TradingOrderBudgetAllocatorTest.java \
        src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java \
        src/test/java/com/kista/trading/application/service/PreviewDepositCacheTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): broker 포트 시그니처 변경에 맞춰 trading/legacy 호출부 매핑 추가

BrokerOrderCorrectionPort.place/cancel, LiveBalancePort.getLiveBalance,
BrokerPricePort.getPriceSnapshot(s) 시그니처 변경에 맞춰 TradingOrderExecutor/
OrderCancelService/TradingReporter/AdminReorderService/TradingOrderBudgetAllocator/
ManualTradingService/TradingPriceFetcher에 broker 타입 ↔ trading 타입 매핑 추가.
TradingReporter는 Execution.direction() 타입 변경(Order.OrderDirection→Direction)
파급도 함께 반영. PreviewDepositCache/StrategyService는 실제로 프로덕션 코드가
영향받지 않음을 확인(전자는 accessor만 체이닝, 후자는 LiveBalancePort 미호출).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 5b (addendum, discovered during dispatch — not in original plan): `Execution`/`DailyTransaction` Direction 타입 파급을 놓친 7개 파일 수정

**왜 필요한가**: Task 4가 `Execution.direction()`/`DailyTransaction.direction()`을 trading의 `Order.OrderDirection`에서 broker의 `Direction`으로 바꾸면서 `Execution implements AccountBalance.Fill`도 제거했다. Task 5의 브리핑은 3개 broker 포트(`BrokerOrderCorrectionPort`/`LiveBalancePort`/`BrokerPricePort`) 호출부만 다뤘을 뿐, `Execution`/`DailyTransaction`을 직접 소비하는 아래 7개 파일의 파급은 다루지 않았다 — Task 5 디스패치 직전 `./gradlew compileJava`로 전체 에러 목록을 확인하다 발견됨. `AccountBalance.Fill` 인터페이스 자체(`Order.OrderDirection direction()`)는 trading 소유라 무변경이지만, `Execution`(broker)을 `Fill`로 감싸는 매핑 어댑터가 어디에도 없다 — Task 4 브리핑 Step 3가 "Task 5에서 그대로 재사용한다"고 명시했던 바로 그 부분이 Task 5 브리핑 작성 시 누락됐다.

**Files:**
- Modify `src/main/java/com/kista/trading/domain/model/AccountBalance.java` — `Fill.of(Execution)`/`Fill.listOf(List<Execution>)` 정적 팩토리 추가 (3개 호출부 공용)
- Modify `src/main/java/com/kista/trading/application/service/TradingReporter.java` — **Task 5 Step 3가 이미 이 파일을 수정함(cancel 호출 + buildReport BUY/SELL)** — 그 결과물 위에 `applyExecutions` 호출부(59번째 줄 부근)만 추가로 고친다(같은 파일 두 번째 수정, 아래 diff는 Task 5 완료 후 상태 기준)
- Modify `src/main/java/com/kista/application/service/admin/AdminTradeCorrectionService.java`
- Modify `src/main/java/com/kista/application/service/account/AccountStatisticsService.java`
- Modify `src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java`
- Modify `src/main/java/com/kista/adapter/in/web/dto/DailyTransactionResponse.java`
- Modify `src/main/java/com/kista/domain/backtest/BacktestEngine.java`
- Modify `src/main/java/com/kista/domain/backtest/FillSimulator.java`

**Interfaces:**
- Consumes: Task 4의 `Execution.direction()`/`DailyTransaction.direction()`이 broker `Direction` 반환
- Produces: `AccountBalance.Fill.of(Execution): Fill`, `AccountBalance.Fill.listOf(List<Execution>): List<Fill>` — Task 8의 최종 컴파일 확인 대상에 포함

- [ ] **Step 1: `AccountBalance.java` — `Fill` 정적 팩토리 추가**

Before(전체):
```java
package com.kista.trading.domain.model;

import java.math.BigDecimal;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

public record AccountBalance(
        int holdings,         // 보유 수량
        BigDecimal avgPrice,  // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit // 통합주문가능금액 (USD, 환전 여부 무관 — TTTC2101R itgr_ord_psbl_amt)
) {
    // applyExecutions()가 필요로 하는 최소 형태 — broker의 Execution이 구현한다(모듈 경계상 trading이 broker를 직접 참조하지 않기 위한 의존성 역전)
    public interface Fill {
        Order.OrderDirection direction();
        int quantity();
        BigDecimal amountUsd();
    }
```

After:
```java
package com.kista.trading.domain.model;

import com.kista.broker.domain.model.Execution;

import java.math.BigDecimal;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

public record AccountBalance(
        int holdings,         // 보유 수량
        BigDecimal avgPrice,  // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit // 통합주문가능금액 (USD, 환전 여부 무관 — TTTC2101R itgr_ord_psbl_amt)
) {
    // applyExecutions()가 필요로 하는 최소 형태 — broker의 Execution은 더 이상 이 인터페이스를 구현하지 않는다
    // (모듈 경계상 broker→trading 참조 금지) — 호출부가 아래 of()/listOf()로 값을 복제해 감싼다
    public interface Fill {
        Order.OrderDirection direction();
        int quantity();
        BigDecimal amountUsd();

        // broker의 Execution 1건 → Fill 매핑 — direction만 broker Direction→trading Order.OrderDirection 변환(값 복제)
        static Fill of(Execution execution) {
            Order.OrderDirection direction = execution.direction() == com.kista.broker.domain.model.Direction.BUY
                    ? Order.OrderDirection.BUY : Order.OrderDirection.SELL;
            return new Fill() {
                @Override public Order.OrderDirection direction() { return direction; }
                @Override public int quantity() { return execution.quantity(); }
                @Override public BigDecimal amountUsd() { return execution.amountUsd(); }
            };
        }

        // broker의 Execution 목록 → Fill 목록 매핑 — applyExecutions(List<? extends Fill>) 호출부 공용
        static List<Fill> listOf(List<Execution> executions) {
            return executions.stream().map(Fill::of).toList();
        }
    }
```
(나머지 `buyTotal`/`isOrderValid`/`hasSufficientDepositFor`/`applyExecutions`/`sumQuantity`/`sumAmount`는 무변경)

- [ ] **Step 2: `TradingReporter.java` — `applyExecutions` 호출부 추가 수정**

이 파일은 Task 5 Step 3에서 이미 `cancelUnresolvedOrders`(cancel 호출)와 `buildReport`(BUY/SELL 비교)를 broker 타입으로 고쳤다. 그 상태 위에서 `applyExecutions` 호출 한 곳만 더 고친다.

Before(Task 5 적용 후 상태):
```java
        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        AccountBalance postBalance = balance.applyExecutions(executions);
```

After:
```java
        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로) — broker Execution → Fill 매핑 경유
        AccountBalance postBalance = balance.applyExecutions(AccountBalance.Fill.listOf(executions));
```
`executions`는 이미 `List<Execution>`(broker) 타입 그대로 유지 — `AccountBalance.Fill.listOf`만 감싸면 된다. import 추가 불필요(`AccountBalance`는 이미 와일드카드로 잡힘, `Fill`은 `AccountBalance`의 nested type이라 `AccountBalance.Fill.listOf(...)`로 FQN 없이 접근 가능).

- [ ] **Step 3: `AdminTradeCorrectionService.java` — direction 변환 + Fill 래핑**

Before:
```java
    // 체결 반영 후 잔고 재계산 + cycle_position 스냅샷 append
    private AccountBalance applyFillAndSnapshot(AdminManualTradeCorrectionCommand.Fill fill, Strategy strategy,
                                                AccountBalance balance, StrategyCycle currentCycle) {
        Execution execution = Execution.ofManualFill(fill.tradeDate(), strategy.ticker(),
                fill.direction(), fill.quantity(), fill.price(), fill.externalOrderId());
        AccountBalance updated = balance.applyExecutions(List.of(execution));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updated, fill.price()));
        return updated;
    }
```

After:
```java
    // 체결 반영 후 잔고 재계산 + cycle_position 스냅샷 append
    private AccountBalance applyFillAndSnapshot(AdminManualTradeCorrectionCommand.Fill fill, Strategy strategy,
                                                AccountBalance balance, StrategyCycle currentCycle) {
        Execution execution = Execution.ofManualFill(fill.tradeDate(), strategy.ticker(),
                toDirection(fill.direction()), fill.quantity(), fill.price(), fill.externalOrderId());
        AccountBalance updated = balance.applyExecutions(List.of(AccountBalance.Fill.of(execution)));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updated, fill.price()));
        return updated;
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static com.kista.broker.domain.model.Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> com.kista.broker.domain.model.Direction.BUY;
            case SELL -> com.kista.broker.domain.model.Direction.SELL;
        };
    }
```
import 추가 불필요(FQN으로 직접 참조, 다른 곳에서 `Direction` 짧은 이름을 쓰지 않으므로 충돌 없음). `toManualOrder`/`validateSellQuantity` 등 나머지 메서드는 여전히 `fill.direction()`(`Order.OrderDirection`)을 trading `Order` 생성에 그대로 쓰므로 무변경.

- [ ] **Step 4: `AccountStatisticsService.java` — direction 변환 2곳**

Before:
```java
    private DailyTransactionResult toDailyTransactionResult(List<Order> filled) {
        List<DailyTransaction> items = filled.stream()
                .filter(o -> o.filledQuantity() != null && o.filledQuantity() > 0)
                .map(o -> {
                    BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                    int qty = o.filledQuantity();
                    BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
                    return new DailyTransaction(
                            o.tradeDate().toString(),
                            null,
                            o.direction(),
                            o.ticker(),
                            o.ticker().name(),
                            qty,
                            price,
                            amount,
                            null,
                            null,
                            "USD"
                    );
                })
                .toList();

        BigDecimal buyTotal = items.stream()
                .filter(t -> t.direction() == Order.OrderDirection.BUY)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = items.stream()
                .filter(t -> t.direction() == Order.OrderDirection.SELL)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
```

After:
```java
    private DailyTransactionResult toDailyTransactionResult(List<Order> filled) {
        List<DailyTransaction> items = filled.stream()
                .filter(o -> o.filledQuantity() != null && o.filledQuantity() > 0)
                .map(o -> {
                    BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                    int qty = o.filledQuantity();
                    BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
                    return new DailyTransaction(
                            o.tradeDate().toString(),
                            null,
                            toDirection(o.direction()),
                            o.ticker(),
                            o.ticker().name(),
                            qty,
                            price,
                            amount,
                            null,
                            null,
                            "USD"
                    );
                })
                .toList();

        BigDecimal buyTotal = items.stream()
                .filter(t -> t.direction() == com.kista.broker.domain.model.Direction.BUY)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = items.stream()
                .filter(t -> t.direction() == com.kista.broker.domain.model.Direction.SELL)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
```
클래스 안 적당한 위치(예: `toDailyTransactionResult` 바로 아래)에 헬퍼 추가:
```java
    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static com.kista.broker.domain.model.Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> com.kista.broker.domain.model.Direction.BUY;
            case SELL -> com.kista.broker.domain.model.Direction.SELL;
        };
    }
```

- [ ] **Step 5: `TradingReportNotifier.java` — static import 교체**

Before:
```java
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradeEvent;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.kista.trading.domain.model.Order.OrderDirection.SELL;
```
```java
        for (Execution e : event.executions()) {
            TradeEvent tradeEvent = e.direction() == SELL
```

After:
```java
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradeEvent;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
```
(static import 줄 삭제)
```java
        for (Execution e : event.executions()) {
            TradeEvent tradeEvent = e.direction() == Direction.SELL
```

**테스트 갱신 — `TradingReportNotifierTest.java` (사전 확인 완료, 실제로 컴파일 깨짐)**: 이 테스트가 `new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.BUY, ...)`로 `Execution`을 직접 생성하는데, `Execution`의 3번째 생성자 파라미터가 이제 broker `Direction`이라 `Order.OrderDirection`을 넘기면 컴파일 에러다.

Before:
```java
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
```
```java
    private static Execution buyExecution() {
        return new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.BUY,
                3, new BigDecimal("20.00"), new BigDecimal("60.00"), "E-BUY");
    }

    private static Execution sellExecution() {
        return new Execution(TODAY, Ticker.SOXL, Order.OrderDirection.SELL,
                2, new BigDecimal("21.00"), new BigDecimal("42.00"), "E-SELL");
    }
```

After:
```java
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
```
```java
    private static Execution buyExecution() {
        return new Execution(TODAY, Ticker.SOXL, Direction.BUY,
                3, new BigDecimal("20.00"), new BigDecimal("60.00"), "E-BUY");
    }

    private static Execution sellExecution() {
        return new Execution(TODAY, Ticker.SOXL, Direction.SELL,
                2, new BigDecimal("21.00"), new BigDecimal("42.00"), "E-SELL");
    }
```
`Order` import는 다른 곳(테스트 나머지)에서 쓰이는지 확인 후 안 쓰이면 삭제 — 이 테스트 파일에 남아있는 유일한 `Order` 참조가 이 두 헬퍼뿐이었다면 제거.

`FillSimulatorTest.java`/`BacktestEngineTest.java`/`AdminTradeCorrectionServiceTest.java`는 (사전 확인 완료) `Execution`/`AccountBalance.Fill`을 직접 생성하거나 `.direction()`을 비교하는 코드가 없다 — 프로덕션 코드만 고치면 별도 테스트 수정 없이 통과해야 한다.

- [ ] **Step 6: `DailyTransactionResponse.java` — `ItemDto.direction` 필드 타입 교체**

Before:
```java
import com.kista.broker.domain.model.DailyTransaction;
import com.kista.broker.domain.model.DailyTransactionResult;
import com.kista.broker.domain.model.DailyTransactionSummary;
import com.kista.trading.domain.model.Order.OrderDirection;
import com.kista.domain.model.strategy.Strategy.Ticker;
```
```java
    public record ItemDto(
            @Schema(description = "매매일 (KST 기준)")
            String tradeDate,
            @Schema(description = "매수/매도 방향", example = "BUY")
            OrderDirection direction,
```

After:
```java
import com.kista.broker.domain.model.DailyTransaction;
import com.kista.broker.domain.model.DailyTransactionResult;
import com.kista.broker.domain.model.DailyTransactionSummary;
import com.kista.broker.domain.model.Direction;
import com.kista.domain.model.strategy.Strategy.Ticker;
```
```java
    public record ItemDto(
            @Schema(description = "매매일 (KST 기준)")
            String tradeDate,
            @Schema(description = "매수/매도 방향", example = "BUY")
            Direction direction,
```
`from()` 팩토리(`t.direction()` 그대로 전달)는 무변경 — 필드 타입만 바뀌면 자동으로 맞는다. JSON 직렬화 결과는 동일(`Direction` enum도 `BUY`/`SELL` 이름 그대로).

- [ ] **Step 7: `BacktestEngine.java` — Fill 래핑 + Direction 비교**

Before:
```java
        // 체결 반영 — 잔고·체결건수 갱신
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            balance = balance.applyExecutions(executions);
            tradeCount += executions.size();
        }
    }
```
```java
        // 공통 체결 반영에 이번 사이클 매수 사용액 누계를 덧붙인다
        @Override
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            super.applyFills(executions);
            poolUsed = poolUsed.add(executions.stream()
                    .filter(e -> e.direction() == BUY)
                    .map(Execution::amountUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
```
import 목록(파일 상단, 이미 확인됨):
```java
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
...
import static com.kista.trading.domain.model.Order.OrderDirection.BUY;
```

After:
```java
        // 체결 반영 — 잔고·체결건수 갱신
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            balance = balance.applyExecutions(AccountBalance.Fill.listOf(executions));
            tradeCount += executions.size();
        }
    }
```
```java
        // 공통 체결 반영에 이번 사이클 매수 사용액 누계를 덧붙인다
        @Override
        void applyFills(List<Execution> executions) {
            if (executions.isEmpty()) return;
            super.applyFills(executions);
            poolUsed = poolUsed.add(executions.stream()
                    .filter(e -> e.direction() == com.kista.broker.domain.model.Direction.BUY)
                    .map(Execution::amountUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
```
import 교체:
```java
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
...
```
**주의(사전 확인 완료)**: `import static com.kista.trading.domain.model.Order.OrderDirection.BUY;`는 **삭제하지 않는다** — 이 파일 안에서 `o.direction() == BUY`(`o`는 trading `Order`) 형태로 220, 283, 350, 361번째 줄에서 정상적으로 계속 쓰인다. 오직 451번째 줄(`e.direction() == BUY`, `e`는 broker `Execution`)만 타입이 다르므로 그 자리만 위처럼 `com.kista.broker.domain.model.Direction.BUY`로 FQN 교체하고, static import 줄과 나머지 4곳의 `BUY` 사용은 그대로 둔다.

- [ ] **Step 8: `FillSimulator.java` — direction 변환 (백테스트 전용 `simulate()`)**

Before:
```java
package com.kista.domain.backtest;

import com.kista.domain.model.backtest.DailyCandle;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
```
```java
    public static List<Execution> simulate(List<Order> pendingOrders, DailyCandle candle) {
        List<Execution> executions = new ArrayList<>();
        for (Order order : pendingOrders) {
            if (!fillsOhlc(order, candle)) continue;
            BigDecimal fillPrice = order.orderType() == Order.OrderType.LIMIT ? order.price() : candle.close();
            executions.add(Execution.ofManualFill(candle.date(), order.ticker(), order.direction(),
                    order.quantity(), fillPrice, order.orderLeg()));
        }
        return executions;
    }
```

After:
```java
package com.kista.domain.backtest;

import com.kista.domain.model.backtest.DailyCandle;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
```
```java
    public static List<Execution> simulate(List<Order> pendingOrders, DailyCandle candle) {
        List<Execution> executions = new ArrayList<>();
        for (Order order : pendingOrders) {
            if (!fillsOhlc(order, candle)) continue;
            BigDecimal fillPrice = order.orderType() == Order.OrderType.LIMIT ? order.price() : candle.close();
            executions.add(Execution.ofManualFill(candle.date(), order.ticker(), toDirection(order.direction()),
                    order.quantity(), fillPrice, order.orderLeg()));
        }
        return executions;
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }
```
`fills(Order, BigDecimal)`/`fillsOhlc(Order, DailyCandle)`는 trading `Order`만 다루므로(broker `Execution` 생성 없음) 무변경.

**리팩토링 관찰(발견만, 이번 태스크에서 수정하지 않음)**: 이 파일 상단 주석 "체결 판정 SSOT — MockBrokerAdapter(모의계좌, 종가 기준)와 BacktestEngine(백테스트, OHLC 기준)이 공용"은 Task 6에서 `MockBrokerAdapter`가 이 `fills()`를 더 이상 호출하지 않고 broker 소유 타입으로 동일 로직을 복제하게 되면 더 이상 사실이 아니게 된다 — Task 8 문서 갱신 단계에서 함께 정리 필요.

- [ ] **Step 9: 컴파일·테스트 검증**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: 이 태스크가 다룬 7개 파일 + `AccountBalance.java` 관련 에러는 사라지고, `com.kista.broker.adapter.out.mock.*`(Task 6, 아직 미착수) 관련 에러만 남아있어야 한다.

```bash
./gradlew test --tests 'com.kista.trading.application.service.TradingReporterTest' \
  --tests 'com.kista.application.service.admin.AdminTradeCorrectionServiceTest' \
  --tests 'com.kista.notify.adapter.out.gateway.TradingReportNotifierTest' \
  --tests 'com.kista.domain.backtest.BacktestEngineTest' \
  --tests 'com.kista.domain.backtest.FillSimulatorTest'
```
(사전 확인 완료: `AdminTradeCorrectionServiceTest`/`TradingReportNotifierTest`/`BacktestEngineTest`/`FillSimulatorTest` 전부 존재. `AccountStatisticsServiceTest`는 존재하지 않으므로 목록에서 제외 — 이 서비스에 대한 단위 테스트 자체가 없다는 뜻, Step 4 수정 후 별도 테스트 작성은 이번 addendum 스코프 밖(발견만 기록))

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/kista/trading/domain/model/AccountBalance.java \
        src/main/java/com/kista/trading/application/service/TradingReporter.java \
        src/main/java/com/kista/application/service/admin/AdminTradeCorrectionService.java \
        src/main/java/com/kista/application/service/account/AccountStatisticsService.java \
        src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java \
        src/main/java/com/kista/adapter/in/web/dto/DailyTransactionResponse.java \
        src/main/java/com/kista/domain/backtest/BacktestEngine.java \
        src/main/java/com/kista/domain/backtest/FillSimulator.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TradingReportNotifierTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): Execution/DailyTransaction Direction 타입 파급 잔여 7곳 수정

Task 4가 Execution.direction()/DailyTransaction.direction()을 broker Direction으로
바꾸면서 Execution implements AccountBalance.Fill도 제거했으나, Task 5 브리핑이
3개 broker 포트 호출부만 다루고 Execution/DailyTransaction을 직접 소비하는 7개
파일(TradingReporter의 applyExecutions 호출, AdminTradeCorrectionService,
AccountStatisticsService, TradingReportNotifier, DailyTransactionResponse,
BacktestEngine, FillSimulator)의 파급을 놓쳤다 — Task 4 브리핑이 "Fill 매핑
어댑터는 Task 5에서 추가"라고 명시했던 부분.

AccountBalance.Fill에 정적 팩토리 of(Execution)/listOf(List<Execution>)를
추가해 3곳(TradingReporter/AdminTradeCorrectionService/BacktestEngine)의
applyExecutions() 호출이 공용으로 쓰도록 하고, 나머지는 Order.OrderDirection→
Direction 변환 또는 필드 타입 교체로 해결. 로직 변경 없음(순수 타입 매핑).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 6: MockBrokerAdapter의 trading persistence 직접 접근 제거

**Files:**
- Create → `src/main/java/com/kista/broker/domain/model/PlacedOrderView.java`
- Create → `src/main/java/com/kista/broker/domain/model/PositionView.java`
- Create → `src/main/java/com/kista/broker/domain/port/out/MockSimulationDataPort.java`
- Create → `src/main/java/com/kista/trading/adapter/out/MockSimulationDataAdapter.java`
- Modify → `src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java`
- Modify → `src/test/java/com/kista/broker/adapter/out/mock/MockBrokerAdapterTest.java`

**Interfaces:**
- Consumes: Task 1의 broker 소유 타입(`Direction, OrderType, PriceSnapshot, BrokerBalance, OrderInstruction, OrderResult, CancelInstruction`) — `MockBrokerAdapter` 자체는 Task 2/3의 파일 목록에서 의도적으로 제외돼 있었으므로 이 태스크가 `MockBrokerAdapter`의 `BrokerPricePort`/`LiveBalancePort`/`BrokerOrderCorrectionPort` 구현을 처음이자 유일하게 갱신한다
- Produces: `MockSimulationDataPort`(broker 소유), `PlacedOrderView`/`PositionView`(broker 소유 뷰 레코드) — Task 8의 최종 검증 대상

**배경**: `MockBrokerAdapter`(broker 모듈)가 trading 소유 `OrderPort`/`CyclePositionPort`/`StrategyCyclePort`를 직접 주입받아 DB 상태를 읽고 써서 실제 증권사 API 없이 체결·잔고를 흉내낸다. 이건 이 프로젝트에 이미 있는 패턴(`AlpacaCalendarAdapter`(`adapter.out.alpaca`) → `MarketHolidayStorePort`(`domain.port.out`) → `MarketCalendarPersistenceAdapter`(`adapter.out.persistence.calendar`))을 **반대 방향으로** 쓰고 있는 셈이다. 여기서는 같은 원리를 뒤집어 적용한다 — 데이터를 필요로 하는 쪽(broker)이 포트를 정의하고, 데이터를 가진 쪽(trading)이 구현한다.

`MockBrokerAdapter.java`의 실제 호출부를 전수 확인한 결과 trading에서 필요한 건 딱 3가지뿐이다: (1) 전략의 현재 활성 `StrategyCycle`의 `id()`(그 외 필드 불필요), (2) 사이클+거래일 기준 `PLACED` 주문들의 `orderType()`/`direction()`/`quantity()`/`price()`/`externalOrderId()`(`ticker()`는 안 씀), (3) 최신 `CyclePosition`의 `holdings()`/`avgPrice()`/`usdDeposit()`. 그래서 새 포트는 범용 CRUD가 아니라 이 3개만 노출한다.

- [ ] **Step 1: `PlacedOrderView`/`PositionView`(broker 소유 뷰 레코드) + `MockSimulationDataPort` 신설**

domain/port/out 인터페이스의 파라미터·반환 타입은 domain/model 하위 최상위 클래스여야 한다(constraints.md "도메인 포트 인터페이스와 타입 위치 규칙") — 포트 인터페이스 안에 nested record로 넣지 않는다.

`src/main/java/com/kista/broker/domain/model/PlacedOrderView.java`:
```java
package com.kista.broker.domain.model;

import java.math.BigDecimal;

// MockBrokerAdapter 체결 시뮬레이션 전용 뷰 — trading 소유 Order 필드 중 실제로 읽는 것만 담는다
// (MockSimulationDataPort.findPlacedOrders 반환 타입 — trading 쪽 MockSimulationDataAdapter가 매핑해 생성)
public record PlacedOrderView(
        Direction direction,       // 매수/매도 방향
        OrderType orderType,       // LOC/MOC/LIMIT — fills() 판정 및 체결가 결정에 사용
        Integer quantity,          // 주문 수량
        BigDecimal price,          // 주문 가격 (LOC/LIMIT 지정가)
        String externalOrderId     // 증권사 부여 주문번호 (모의계좌는 MOCK- 접두사 합성값)
) {}
```

`src/main/java/com/kista/broker/domain/model/PositionView.java`:
```java
package com.kista.broker.domain.model;

import java.math.BigDecimal;

// MockBrokerAdapter 잔고 시뮬레이션 전용 뷰 — trading 소유 CyclePosition 필드 중 실제로 읽는 것만 담는다
// (MockSimulationDataPort.findLatestPosition 반환 타입 — trading 쪽 MockSimulationDataAdapter가 매핑해 생성)
public record PositionView(
        int holdings,          // 보유 수량
        BigDecimal avgPrice,   // 평균 매입 단가 (holdings=0이면 null)
        BigDecimal usdDeposit  // 통합주문가능금액
) {}
```

`src/main/java/com/kista/broker/domain/port/out/MockSimulationDataPort.java`:
```java
package com.kista.broker.domain.port.out;

import com.kista.broker.domain.model.PlacedOrderView;
import com.kista.broker.domain.model.PositionView;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// MockBrokerAdapter 전용 — 실제 증권사 API 없이 trading이 소유한 영속 데이터(주문·사이클·포지션)를 조회하기 위한 broker 소유 포트.
// AlpacaCalendarAdapter(adapter.out.alpaca) → MarketHolidayStorePort(domain.port.out) → MarketCalendarPersistenceAdapter
// (persistence.calendar) 패턴을 역방향 적용한 것 — 데이터를 필요로 하는 쪽(broker)이 포트를 정의하고,
// 데이터를 가진 쪽(trading)이 구현한다. 반환 타입도 broker 소유 얇은 뷰 레코드(Order/CyclePosition 전체가 아닌
// MockBrokerAdapter가 실제로 읽는 필드만)로 제한한다.
public interface MockSimulationDataPort {

    // 전략의 현재 활성 사이클 ID — StrategyCycle 전체가 아닌 id만 필요
    UUID findActiveCycleId(UUID strategyId);

    // 사이클·거래일 기준 PLACED 주문 조회 (체결 시뮬레이션 대상)
    List<PlacedOrderView> findPlacedOrders(UUID cycleId, LocalDate tradeDate);

    // 전략 기준 최신 포지션 스냅샷 (없으면 empty — 아직 체결 이력 없음)
    Optional<PositionView> findLatestPosition(UUID strategyId);
}
```

- [ ] **Step 2: `MockSimulationDataAdapter` 신설 (trading 소유, 포트 구현)**

내부적으로 trading 자신의 `OrderPort`/`CyclePositionPort`/`StrategyCyclePort`를 호출한다(같은 모듈 내부 호출이라 경계 문제 없음). `CycleLookups.requireLatestCycle`(`com.kista.common`, 기존에도 trading이 쓰던 헬퍼)을 그대로 재사용한다.

`src/main/java/com/kista/trading/adapter/out/MockSimulationDataAdapter.java`:
```java
package com.kista.trading.adapter.out;

import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderType;
import com.kista.broker.domain.model.PlacedOrderView;
import com.kista.broker.domain.model.PositionView;
import com.kista.broker.domain.port.out.MockSimulationDataPort;
import com.kista.common.CycleLookups;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.port.out.CyclePositionPort;
import com.kista.trading.domain.port.out.OrderPort;
import com.kista.trading.domain.port.out.StrategyCyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// MockBrokerAdapter(broker 모듈)가 정의한 MockSimulationDataPort 구현체 — trading 소유 영속 포트(OrderPort/
// CyclePositionPort/StrategyCyclePort)를 내부적으로 호출해 broker 소유 뷰 레코드로 매핑한다.
// trading→broker(정상, 유지 방향) 참조만 발생 — broker는 더 이상 trading 타입을 참조하지 않는다.
@Component
@RequiredArgsConstructor
class MockSimulationDataAdapter implements MockSimulationDataPort {

    private final OrderPort orderPort;
    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;

    @Override
    public UUID findActiveCycleId(UUID strategyId) {
        return CycleLookups.requireLatestCycle(strategyCyclePort, strategyId).id();
    }

    @Override
    public List<PlacedOrderView> findPlacedOrders(UUID cycleId, LocalDate tradeDate) {
        return orderPort.findPlacedByCycleAndDate(cycleId, tradeDate).stream()
                .map(MockSimulationDataAdapter::toPlacedOrderView)
                .toList();
    }

    @Override
    public Optional<PositionView> findLatestPosition(UUID strategyId) {
        return cyclePositionPort.findLatestOneByStrategyId(strategyId).map(MockSimulationDataAdapter::toPositionView);
    }

    private static PlacedOrderView toPlacedOrderView(Order order) {
        return new PlacedOrderView(toDirection(order.direction()), toOrderType(order.orderType()),
                order.quantity(), order.price(), order.externalOrderId());
    }

    private static PositionView toPositionView(CyclePosition position) {
        return new PositionView(position.holdings(), position.avgPrice(), position.usdDeposit());
    }

    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }

    private static OrderType toOrderType(Order.OrderType orderType) {
        return switch (orderType) {
            case LOC -> OrderType.LOC;
            case MOC -> OrderType.MOC;
            case LIMIT -> OrderType.LIMIT;
        };
    }
}
```

**주의**: 위 `findPlacedByCycleAndDate`/`findLatestOneByStrategyId` 메서드명은 설계 의도를 따른 추정이다 — 실제 `OrderPort`/`CyclePositionPort` 인터페이스(`src/main/java/com/kista/trading/domain/port/out/{OrderPort,CyclePositionPort}.java`)를 열어 `MockBrokerAdapter`가 지금 호출하고 있는 정확한 메서드명·시그니처로 맞춰 쓸 것 — 이름이 다르면 그 실제 이름을 그대로 사용한다(신규 메서드 추가가 필요하다면 기존 포트에 한 줄 추가하되, 로직은 기존 구현을 그대로 재사용).

- [ ] **Step 3: `MockBrokerAdapter` 전체 재작성**

`OrderPort`/`StrategyCyclePort`/`CyclePositionPort` 3개 필드를 `MockSimulationDataPort` 1개로 교체한다. 동시에 `BrokerPricePort`/`LiveBalancePort`/`BrokerOrderCorrectionPort` 시그니처를 broker 소유 타입(`PriceSnapshot`/`BrokerBalance`/`OrderInstruction`·`OrderResult`·`CancelInstruction`)으로 맞춘다. `FillSimulator.fills(Order, BigDecimal)`(`com.kista.domain.backtest`)는 trading의 `Order`를 받으므로 더 이상 호출할 수 없다 — 동일한 판정 로직을 broker 소유 타입으로 재구현한다(`PersistenceSupport`/`DstInfo` 부분 복제와 동일 판단 기준 — 순수 판정 로직이 짧아 포트 우회보다 복제가 저렴함).

`src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java` 전체를 아래로 교체:
```java
package com.kista.broker.adapter.out.mock;

import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.broker.domain.model.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.StrategyPort;
import com.kista.broker.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 모의계좌 어댑터 — 실제 증권사 접수 없이 DB 스냅샷 기반으로 잔고·체결을 시뮬레이션
// trading 소유 영속 데이터는 MockSimulationDataPort(broker 소유 포트, trading이 구현) 경유로만 접근 — trading 타입 직접 참조 없음
@Component
@RequiredArgsConstructor
public class MockBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort {

    private final CommonMarketPriceFeed priceFeed;               // 시세 재사용 — Spring이 TossPriceApi 빈을 이 인터페이스로 주입
    private final StrategyPort strategyPort;                     // 계좌+ticker → strategy 해석 (legacy 공개 포트)
    private final MockSimulationDataPort mockSimulationDataPort; // trading 소유 주문·사이클·포지션 조회 (포트 역전 — 클래스 주석 참고)

    @Override
    public Account.Broker supports() {
        return Account.Broker.MOCK;
    }

    // --- 계좌+ticker → 전략 해석 공통 헬퍼 ---
    // Account에는 ticker 정보가 없다(전략이 소유) — 계좌에 속한 전략 중 ticker가 일치하는 것을 찾는다
    private Strategy resolveStrategy(Account account, Ticker ticker) {
        return strategyPort.findByAccountId(account.id()).stream()
                .filter(s -> s.ticker() == ticker)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌에 해당 종목 전략이 없습니다: accountId=" + account.id() + ", ticker=" + ticker));
    }

    // --- 계좌+ticker → 최신 포지션 해석 공통 헬퍼 ---
    private PositionView resolveLatestPosition(Account account, Ticker ticker) {
        Strategy strategy = resolveStrategy(account, ticker);
        return mockSimulationDataPort.findLatestPosition(strategy.id())
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌 포지션 이력이 없습니다: strategyId=" + strategy.id()));
    }

    // 계좌 전체 가용 예수금 — 실제 브로커는 계좌 단일 현금풀을 여러 전략이 공유하므로(TradingOrderBudgetAllocator가
    // 대표 전략 1개로 getLiveBalance를 호출해 계좌의 모든 BUY 후보에 그대로 적용) 모의계좌도 전략별 usdDeposit을
    // 합산해 계좌 단위 값으로 맞춘다 — 전략별 값을 그대로 반환하면 다른 전략의 잔고로 매수 승인/거절이 오염된다
    private BigDecimal sumUsdDepositAcrossStrategies(Account account) {
        return strategyPort.findByAccountId(account.id()).stream()
                .map(s -> mockSimulationDataPort.findLatestPosition(s.id()))
                .flatMap(Optional::stream)
                .map(PositionView::usdDeposit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- BrokerPricePort (account 파라미터 무시, priceFeed에 위임 — Toss 패턴과 동일) ---

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        return priceFeed.getPrice(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return priceFeed.getPrices(tickers); // 공통 API — account 불필요
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return toBrokerSnapshot(priceFeed.getPriceSnapshot(ticker)); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        Map<Ticker, PriceSnapshot> result = new LinkedHashMap<>();
        priceFeed.getPriceSnapshots(tickers).forEach((t, snapshot) -> result.put(t, toBrokerSnapshot(snapshot)));
        return result;
    }

    // priceFeed(CommonMarketPriceFeed, legacy OPEN 모듈)는 trading 소유 PriceSnapshot을 반환한다 — BrokerPricePort는
    // broker 소유 PriceSnapshot을 요구하므로 경계에서 필드를 그대로 복제해 변환한다(필드 구성 동일).
    // 두 타입 모두 단순 이름이 PriceSnapshot이라 broker 쪽은 위 와일드카드 import로, trading 쪽은 FQN으로 구분한다
    private static PriceSnapshot toBrokerSnapshot(com.kista.trading.domain.model.PriceSnapshot snapshot) {
        return snapshot == null ? null : new PriceSnapshot(snapshot.current(), snapshot.prevClose());
    }

    @Override
    public BigDecimal getPrevClose(Ticker ticker, Account account) {
        return priceFeed.getPrevClose(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account) {
        return priceFeed.getPrevCloses(tickers); // 공통 API — account 불필요
    }

    // tradeDate 일봉 확정 종가 — 시세는 Toss 공용 피드 재사용(CommonMarketPriceFeed.getClosingPrice)
    @Override
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return priceFeed.getClosingPrice(ticker, tradeDate);
    }

    @Override
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        for (Ticker ticker : tickers) {
            result.put(ticker, priceFeed.getClosingPrice(ticker, tradeDate));
        }
        return result;
    }

    // --- LiveBalancePort ---

    @Override
    public BrokerBalance getLiveBalance(Account account, Ticker ticker) {
        // usdDeposit은 계좌 전체 합산(위 sumUsdDepositAcrossStrategies 주석 참고), holdings/avgPrice는 해당 ticker 전략 값
        PositionView position = resolveLatestPosition(account, ticker);
        return new BrokerBalance(position.holdings(), position.avgPrice(), sumUsdDepositAcrossStrategies(account));
    }

    // --- SellableQuantityPort ---

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        int holdings = resolveLatestPosition(account, ticker).holdings();
        return new SellableQuantity(ticker.name(), holdings);
    }

    // --- BrokerOrderCorrectionPort ---

    @Override
    public OrderResult place(OrderInstruction instruction, Account account) {
        // 실제 증권사 접수 없이 합성 주문번호 부여 — 이 ID를 getExecutions()가 그대로 echo해 TradingReporter.markFilledOrders와 매칭시킨다
        return new OrderResult("MOCK-" + UUID.randomUUID());
    }

    @Override
    public void cancel(CancelInstruction instruction, Account account) {
        // no-op — 모의계좌는 별도 취소 대상이 없음(getExecutions에서 미체결 주문은 TradingReporter가 자체적으로 CANCELLED 처리)
    }

    // --- ExecutionPort — 체결 시뮬레이션 코어 ---
    // MOC: 항상 체결(종가) / LOC: 매수는 종가<=지정가, 매도는 종가>=지정가 (체결가는 종가)
    // LIMIT: 매수는 종가<=지정가, 매도는 종가>=지정가 (체결가는 지정가 그대로 — LOC와 달리 종가로 재계산하지 않음)
    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        // 실제 호출부(TradingReporter)는 항상 from==to(당일)로만 호출 — to를 거래일로 사용
        // cycleId로 스코프 — account+ticker만으로 조회하면 사이클 롤오버 당일 종료된 이전 사이클의
        // 잔류 PLACED 주문(취소 실패 등)이 새 사이클의 체결에 잘못 합산될 수 있어 활성 사이클 격리 조회를 재사용한다
        Strategy strategy = resolveStrategy(account, ticker);
        UUID cycleId = mockSimulationDataPort.findActiveCycleId(strategy.id());
        List<PlacedOrderView> placed = mockSimulationDataPort.findPlacedOrders(cycleId, to);
        if (placed.isEmpty()) return List.of();

        BigDecimal closingPrice = getClosingPrice(ticker, to, account);
        List<Execution> executions = new ArrayList<>();
        for (PlacedOrderView order : placed) {
            if (!fills(order, closingPrice)) continue; // 미체결 — TradingReporter.markFilledOrders가 CANCELLED로 기록
            // LIMIT은 지정가 그대로 체결, LOC/MOC는 종가 기준 체결
            BigDecimal fillPrice = order.orderType() == OrderType.LIMIT ? order.price() : closingPrice;
            executions.add(Execution.ofManualFill(to, ticker, order.direction(), order.quantity(), fillPrice, order.externalOrderId()));
        }
        return executions;
    }

    // 체결 판정 SSOT는 com.kista.domain.backtest.FillSimulator.fills(Order, BigDecimal)이지만 trading의 Order를 받는다 —
    // broker는 더 이상 trading 타입을 참조할 수 없으므로 동일 판정 로직을 broker 소유 타입으로 재구현한다.
    // 순수 3줄 판정이라 포트 우회보다 저비용 복제로 판단(PersistenceSupport/DstInfo 부분 복제와 동일 기준, 변경 금지)
    private static boolean fills(PlacedOrderView order, BigDecimal closingPrice) {
        if (order.orderType() == OrderType.MOC) return true;
        return order.direction() == Direction.BUY
                ? closingPrice.compareTo(order.price()) <= 0
                : closingPrice.compareTo(order.price()) >= 0;
    }

    // --- MarginPort ---

    // 신규 전략 등록 시점 게이트체크(StrategyService.calcFreeCash) 전용 — 등록하려는 전략은 아직 cycle_position이
    // 없어(chicken-and-egg) 실제 잔고를 계산할 수 없으므로 상한 없음으로 항상 통과시킨다.
    // 실제 매수 예산 제약은 배치 실행 시 LiveBalancePort.getLiveBalance()(계좌 합산)가 담당한다.
    // 주의: 이 값은 GET /api/accounts/{id}/margin에도 그대로 노출되므로 화면상 "가용현금"이 실제와 다르게 크게
    // 보일 수 있다 — 모의계좌 UI에서 이 필드는 참고용이 아님을 별도 안내하는 것을 권장한다.
    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return new BigDecimal("999999999.00");
    }

    @Override
    public List<MarginItem> getMargin(Account account) {
        BigDecimal buyable = getUsdBuyableAmount(account);
        return List.of(new MarginItem(Currency.USD, buyable, buyable, buyable, BigDecimal.ONE));
    }

    // --- PortfolioPort ---

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        List<Strategy> strategies = strategyPort.findByAccountId(account.id());
        List<PresentBalanceResult.TossHolding> holdings = new ArrayList<>();
        BigDecimal totalUsdDeposit = BigDecimal.ZERO;
        for (Strategy strategy : strategies) {
            Optional<PositionView> latest = mockSimulationDataPort.findLatestPosition(strategy.id());
            if (latest.isEmpty()) continue;
            PositionView position = latest.get();
            totalUsdDeposit = totalUsdDeposit.add(position.usdDeposit());
            if (position.holdings() > 0 && position.avgPrice() != null) {
                BigDecimal currentPrice = priceFeed.getPrice(strategy.ticker());
                holdings.add(new PresentBalanceResult.TossHolding(strategy.ticker(), position.holdings(), position.avgPrice(), currentPrice));
            }
        }
        // 모의계좌는 KRW 환전 개념이 없음 — krwDeposit=0, rate=0으로 집계(문서화된 근사치, DB 스냅샷 기반)
        // rate=0이면 aggregateToss()가 hasRate=false 경로로 0으로 나누지 않고 안전하게 처리
        return PresentBalanceResult.aggregateToss(holdings, totalUsdDeposit, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
```

**주의**: 원본 `MockBrokerAdapter`에 이미 있던 필드/메서드 중 위 재작성본에 반영되지 않은 것이 있다면(예: `getMargin`/`getPresentBalance`의 실제 필드 구성이 다르거나, 원본에 이 초안이 놓친 메서드가 더 있는 경우) — 구현 착수 직전 반드시 `git show HEAD:src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java`(또는 현재 워킹트리 파일)로 원본 전체를 재확인하고, 이 초안과 다른 부분은 원본 로직을 그대로 유지한 채 타입만 교체할 것 — 이 Step은 로직 변경이 아니라 순수 타입/배선 전환이 목적이다.

- [ ] **Step 4: `MockBrokerAdapterTest` 전체 재작성**

Mock 대상을 `OrderPort`/`StrategyCyclePort`/`CyclePositionPort` 3개에서 `MockSimulationDataPort` 1개로 교체한다.

`src/test/java/com/kista/broker/adapter/out/mock/MockBrokerAdapterTest.java` 전체를 아래로 교체:
```java
package com.kista.broker.adapter.out.mock;

import com.kista.adapter.out.marketdata.CommonMarketPriceFeed;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.broker.domain.model.PlacedOrderView;
import com.kista.broker.domain.model.PositionView;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.broker.domain.port.out.MockSimulationDataPort;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.StrategyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 모의계좌 어댑터 — DB 스냅샷 기반 잔고·체결 시뮬레이션 검증
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.parallel.Execution(ExecutionMode.SAME_THREAD)
class MockBrokerAdapterTest {

    @Mock
    private CommonMarketPriceFeed priceFeed;

    @Mock
    private StrategyPort strategyPort;

    @Mock
    private MockSimulationDataPort mockSimulationDataPort;

    private MockBrokerAdapter adapter() {
        return new MockBrokerAdapter(priceFeed, strategyPort, mockSimulationDataPort);
    }

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID CYCLE_ID = UUID.randomUUID();
    private static final Account ACCOUNT = new Account(ACCOUNT_ID, UUID.randomUUID(), "모의계좌",
            "12345678", "key", "secret", null, Account.Broker.MOCK, null);
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 25);
    private static final Strategy TQQQ_STRATEGY = new Strategy(STRATEGY_ID, ACCOUNT_ID, Strategy.Type.VR,
            Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);

    // getExecutions()가 strategy→cycle을 해석할 수 있도록 공통 stub — 개별 테스트는 findPlacedOrders만 stub하면 된다
    private void stubTqqqCycle() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        when(mockSimulationDataPort.findActiveCycleId(STRATEGY_ID)).thenReturn(CYCLE_ID);
    }

    // 테스트용 PLACED 주문 뷰 생성 헬퍼
    private static PlacedOrderView placedOrder(OrderType orderType, Direction direction,
                                                int quantity, BigDecimal price, String externalOrderId) {
        return new PlacedOrderView(direction, orderType, quantity, price, externalOrderId);
    }

    @Test
    @DisplayName("supports()는 MOCK을 반환한다")
    void supportsReturnsMock() {
        assertThat(adapter().supports()).isEqualTo(Account.Broker.MOCK);
    }

    // --- getExecutions() 체결 시뮬레이션 규칙표 ---

    @Test
    @DisplayName("MOC 주문은 지정가와 무관하게 항상 체결되며 체결가는 종가다")
    void mocOrderAlwaysFills() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.MOC, Direction.BUY, 10,
                new BigDecimal("100.00"), "MOCK-1");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("999.99"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        Execution execution = executions.get(0);
        assertThat(execution.price()).isEqualByComparingTo("999.99");
        assertThat(execution.quantity()).isEqualTo(10);
        assertThat(execution.direction()).isEqualTo(Direction.BUY);
        assertThat(execution.externalOrderId()).isEqualTo("MOCK-1");
    }

    @Test
    @DisplayName("LOC 매수는 종가<=지정가면 체결(체결가=종가), 종가>지정가면 미체결이다")
    void locBuyFillsWhenClosingPriceLteLimit() {
        stubTqqqCycle();
        PlacedOrderView fillable = placedOrder(OrderType.LOC, Direction.BUY, 5,
                new BigDecimal("100.00"), "MOCK-2");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(fillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("95.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("LOC 매수는 종가>지정가면 미체결이다")
    void locBuyDoesNotFillWhenClosingPriceExceedsLimit() {
        stubTqqqCycle();
        PlacedOrderView unfillable = placedOrder(OrderType.LOC, Direction.BUY, 5,
                new BigDecimal("100.00"), "MOCK-3");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(unfillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("105.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LOC 매도는 종가>=지정가면 체결(체결가=종가), 종가<지정가면 미체결이다")
    void locSellFillsWhenClosingPriceGteLimit() {
        stubTqqqCycle();
        PlacedOrderView fillable = placedOrder(OrderType.LOC, Direction.SELL, 5,
                new BigDecimal("100.00"), "MOCK-4");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(fillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("105.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("105.00");
    }

    @Test
    @DisplayName("LOC 매도는 종가<지정가면 미체결이다")
    void locSellDoesNotFillWhenClosingPriceBelowLimit() {
        stubTqqqCycle();
        PlacedOrderView unfillable = placedOrder(OrderType.LOC, Direction.SELL, 5,
                new BigDecimal("100.00"), "MOCK-5");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(unfillable));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("95.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매수는 종가<=지정가면 체결하되 체결가는 지정가 그대로다")
    void limitBuyFillsAtOrderPriceNotClosingPrice() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, Direction.BUY, 5,
                new BigDecimal("100.00"), "MOCK-6");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("90.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        // LOC와 달리 체결가는 종가(90.00)가 아니라 지정가(100.00) 그대로
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIMIT 매수는 종가>지정가면 미체결이다")
    void limitBuyDoesNotFillWhenClosingPriceExceedsLimit() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, Direction.BUY, 5,
                new BigDecimal("100.00"), "MOCK-7");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("110.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("LIMIT 매도는 종가>=지정가면 체결하되 체결가는 지정가 그대로다")
    void limitSellFillsAtOrderPriceNotClosingPrice() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, Direction.SELL, 5,
                new BigDecimal("100.00"), "MOCK-8");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("110.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LIMIT 매도는 종가<지정가면 미체결이다")
    void limitSellDoesNotFillWhenClosingPriceBelowLimit() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LIMIT, Direction.SELL, 5,
                new BigDecimal("100.00"), "MOCK-9");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("90.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("경계값 — 종가==지정가면 등호 포함 조건이므로 LOC 매수도 체결된다")
    void locBuyFillsWhenClosingPriceEqualsLimit() {
        stubTqqqCycle();
        PlacedOrderView order = placedOrder(OrderType.LOC, Direction.BUY, 5,
                new BigDecimal("100.00"), "MOCK-10");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("100.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("PLACED 주문이 없으면 빈 리스트를 반환하고 시세를 조회하지 않는다")
    void returnsEmptyWhenNoPlacedOrders() {
        stubTqqqCycle();
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of());

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("getExecutions는 cycleId로 스코프된 주문만 조회한다 — 다른 사이클 주문은 대상이 아니다")
    void getExecutionsScopesByCurrentActiveCycle() {
        stubTqqqCycle();
        // 활성 사이클(CYCLE_ID)만 stub — findPlacedOrders가 다른 cycleId로 호출되면 stub 미스로 실패한다
        PlacedOrderView order = placedOrder(OrderType.MOC, Direction.BUY, 3,
                new BigDecimal("100.00"), "MOCK-CYCLE-SCOPED");
        when(mockSimulationDataPort.findPlacedOrders(CYCLE_ID, TRADE_DATE)).thenReturn(List.of(order));
        when(priceFeed.getClosingPrice(eq(Ticker.TQQQ), any())).thenReturn(new BigDecimal("100.00"));

        List<Execution> executions = adapter().getExecutions(TRADE_DATE, TRADE_DATE, Ticker.TQQQ, ACCOUNT);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).externalOrderId()).isEqualTo("MOCK-CYCLE-SCOPED");
    }

    // --- place()/cancel() ---

    @Test
    @DisplayName("place()는 MOCK- 접두사 합성 externalOrderId를 담은 OrderResult를 반환한다")
    void placeAssignsSyntheticOrderId() {
        OrderInstruction instruction = new OrderInstruction(Ticker.TQQQ, Direction.BUY, OrderType.LOC,
                10, new BigDecimal("100.00"));

        OrderResult result = adapter().place(instruction, ACCOUNT);

        assertThat(result.externalOrderId()).startsWith("MOCK-");
    }

    @Test
    @DisplayName("cancel()은 예외 없이 아무 것도 하지 않는다")
    void cancelIsNoOp() {
        CancelInstruction instruction = new CancelInstruction(Ticker.TQQQ, "MOCK-11");

        adapter().cancel(instruction, ACCOUNT);

        verifyNoInteractions(mockSimulationDataPort, strategyPort, priceFeed);
    }

    // --- getLiveBalance() ---

    @Test
    @DisplayName("getLiveBalance는 holdings/avgPrice는 해당 ticker 전략 값, usdDeposit은 계좌 전체 전략 합산 값을 반환한다")
    void getLiveBalanceSumsUsdDepositAcrossStrategiesButKeepsTickerSpecificHoldings() {
        Strategy soxlStrategy = new Strategy(UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(soxlStrategy, TQQQ_STRATEGY));

        PositionView soxlPosition = new PositionView(4, new BigDecimal("38.00"), new BigDecimal("300.00"));
        PositionView tqqqPosition = new PositionView(10, new BigDecimal("48.00"), new BigDecimal("500.00"));
        when(mockSimulationDataPort.findLatestPosition(soxlStrategy.id())).thenReturn(Optional.of(soxlPosition));
        when(mockSimulationDataPort.findLatestPosition(STRATEGY_ID)).thenReturn(Optional.of(tqqqPosition));

        BrokerBalance balance = adapter().getLiveBalance(ACCOUNT, Ticker.TQQQ);

        // holdings/avgPrice는 TQQQ 전략 고유값
        assertThat(balance.holdings()).isEqualTo(10);
        assertThat(balance.avgPrice()).isEqualByComparingTo("48.00");
        // usdDeposit은 SOXL(300)+TQQQ(500) 계좌 전체 합산 — 다른 전략 예산으로 매수 오판정을 막기 위함
        assertThat(balance.usdDeposit()).isEqualByComparingTo("800.00");
    }

    @Test
    @DisplayName("getLiveBalance는 해당 ticker 전략이 없으면 IllegalStateException을 던진다")
    void getLiveBalanceThrowsWhenNoMatchingStrategy() {
        Strategy soxlStrategy = new Strategy(UUID.randomUUID(), ACCOUNT_ID, Strategy.Type.PRIVACY,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(soxlStrategy));

        MockBrokerAdapter adapter = adapter();
        assertThatThrownBy(() -> adapter.getLiveBalance(ACCOUNT, Ticker.TQQQ))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("getLiveBalance는 전략은 있지만 포지션 이력이 없으면 IllegalStateException을 던진다")
    void getLiveBalanceThrowsWhenNoPositionHistory() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        when(mockSimulationDataPort.findLatestPosition(STRATEGY_ID)).thenReturn(Optional.empty());

        MockBrokerAdapter adapter = adapter();
        assertThatThrownBy(() -> adapter.getLiveBalance(ACCOUNT, Ticker.TQQQ))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- getSellableQuantity() ---

    @Test
    @DisplayName("getSellableQuantity는 최신 포지션의 holdings를 반환한다")
    void getSellableQuantityReturnsLatestHoldings() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        PositionView position = new PositionView(7, new BigDecimal("48.00"), new BigDecimal("500.00"));
        when(mockSimulationDataPort.findLatestPosition(STRATEGY_ID)).thenReturn(Optional.of(position));

        SellableQuantity sellable = adapter().getSellableQuantity(Ticker.TQQQ, ACCOUNT);

        assertThat(sellable.symbol()).isEqualTo(Ticker.TQQQ.name());
        assertThat(sellable.quantity()).isEqualTo(7);
    }

    // --- getUsdBuyableAmount() ---

    @Test
    @DisplayName("getUsdBuyableAmount는 매우 큰 상수 값을 반환한다 (신규 전략 등록 게이트체크 전용)")
    void getUsdBuyableAmountReturnsLargeConstant() {
        BigDecimal result = adapter().getUsdBuyableAmount(ACCOUNT);

        assertThat(result).isGreaterThan(BigDecimal.valueOf(1_000_000));
    }

    // --- BrokerPricePort 위임 ---

    @Test
    @DisplayName("getPrice는 account와 무관하게 CommonMarketPriceFeed로 위임한다")
    void getPriceDelegatesToPriceFeed() {
        when(priceFeed.getPrice(Ticker.TQQQ)).thenReturn(new BigDecimal("123.45"));

        BigDecimal price = adapter().getPrice(Ticker.TQQQ, ACCOUNT);

        assertThat(price).isEqualByComparingTo("123.45");
    }

    @Test
    @DisplayName("getPrevClose는 account와 무관하게 CommonMarketPriceFeed로 위임한다")
    void getPrevCloseDelegatesToPriceFeed() {
        when(priceFeed.getPrevClose(Ticker.TQQQ)).thenReturn(new BigDecimal("120.00"));

        BigDecimal prevClose = adapter().getPrevClose(Ticker.TQQQ, ACCOUNT);

        assertThat(prevClose).isEqualByComparingTo("120.00");
    }

    // --- 스모크: getPresentBalance / getMargin ---

    @Test
    @DisplayName("getPresentBalance는 예외 없이 값을 채워 반환한다")
    void getPresentBalanceSmokeTest() {
        when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(TQQQ_STRATEGY));
        PositionView position = new PositionView(7, new BigDecimal("48.00"), new BigDecimal("500.00"));
        when(mockSimulationDataPort.findLatestPosition(STRATEGY_ID)).thenReturn(Optional.of(position));
        when(priceFeed.getPrice(Ticker.TQQQ)).thenReturn(new BigDecimal("55.00"));

        PresentBalanceResult result = adapter().getPresentBalance(ACCOUNT);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getMargin은 예외 없이 값을 채워 반환한다")
    void getMarginSmokeTest() {
        List<MarginItem> margin = adapter().getMargin(ACCOUNT);

        assertThat(margin).isNotEmpty();
        assertThat(margin.get(0).currency()).isEqualTo(com.kista.broker.domain.model.Currency.USD);
    }
}
```

**주의**: 원본 `MockBrokerAdapterTest`에 이미 있던 테스트 케이스 중 위 재작성본이 놓친 것이 있다면(예: 원본 테스트 클래스에 이 초안이 다루지 않는 시나리오가 더 있는 경우) 구현 착수 직전 원본 전체를 재확인해 그 케이스들도 동일한 방식(trading 타입 → broker 뷰 레코드/`MockSimulationDataPort` mock)으로 이식할 것.

- [ ] **Step 5: compileJava/compileTestJava 검증**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
./gradlew compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: 둘 다 `BUILD SUCCESSFUL`. 에러가 나면 Task 1~3(broker 소유 타입·포트 시그니처·KIS/Toss 어댑터)이 먼저 적용됐는지부터 확인한다.

- [ ] **Step 6: 타겟 테스트 실행**

```bash
./gradlew test --tests 'com.kista.broker.adapter.out.mock.*' --tests 'com.kista.trading.adapter.out.*' --tests 'com.kista.architecture.*'
```
Expected: `MockBrokerAdapterTest` 전체 PASS. `ModulithArchitectureTest`는 이 시점 기준 `broker → trading → broker`와 `broker → trading → notify → broker` 2개 순환은 해소되지만 `notify → trading → notify` 1개는 아직 남아있어야 정상이다(Task 7에서 trading→notify 호출을 이벤트로 전환해야 완전히 사라짐) — 이 시점에 순환이 0개거나 3개 모두 남아있으면 이전 태스크 반영 여부를 재확인한다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/broker/domain/model/PlacedOrderView.java \
        src/main/java/com/kista/broker/domain/model/PositionView.java \
        src/main/java/com/kista/broker/domain/port/out/MockSimulationDataPort.java \
        src/main/java/com/kista/trading/adapter/out/MockSimulationDataAdapter.java \
        src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java \
        src/test/java/com/kista/broker/adapter/out/mock/MockBrokerAdapterTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): MockBrokerAdapter의 trading persistence 직접 접근 제거 — MockSimulationDataPort 포트 역전

MockBrokerAdapter(broker)가 trading 소유 OrderPort/CyclePositionPort/StrategyCyclePort를 직접
주입받아 DB 상태를 읽던 방식을 걷어내고, broker가 정의하고 trading이 구현하는
MockSimulationDataPort(+ PlacedOrderView/PositionView 얇은 뷰 레코드)로 역전했다.
AlpacaCalendarAdapter → MarketHolidayStorePort → MarketCalendarPersistenceAdapter 패턴과 동일한
원리를 반대 방향(데이터를 필요로 하는 쪽이 포트를 정의)으로 적용해 broker→trading 참조 0을
달성한다. 동시에 MockBrokerAdapter의 BrokerPricePort/LiveBalancePort/BrokerOrderCorrectionPort
구현도 broker 소유 타입(PriceSnapshot/BrokerBalance/OrderInstruction·OrderResult·
CancelInstruction)에 맞춰 갱신했다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

---

### Task 7: trading→notify 직접 호출 11곳 → 이벤트 발행 전환

**Files:**
- Create → 6개 이벤트 레코드 `src/main/java/com/kista/trading/application/event/{TradingErrorEvent,InsufficientBalanceEvent,MarketClosedEvent,MarketOpenEvent,MarketCloseEvent,BatchInterruptedEvent}.java`
- Modify → `src/main/java/com/kista/trading/application/event/package-info.java` (공개 이벤트 목록 주석 갱신 — 아직 커밋 안 된 untracked 파일이므로 파일 내용을 직접 다시 작성)
- Create → `src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java`
- Modify → 11개 trading 파일: `adapter/in/schedule/{TradingOpenScheduler,BatchContextFactory}.java`, `application/service/{TradingPriceFetcher,TradingService,ManualTradingService,TradingReporter,MarketEventNotifier,VrCycleRolloverService,CycleRotationService,TradingOrderExecutor,VrReconfigureService}.java`
- Modify → 10개 대응 테스트(목록·상세는 Step 9) + Create → `src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java`

**Interfaces:**
- Consumes: 없음(이전 태스크의 broker 타입 변경과 독립적으로 적용 가능 — notify 관련 줄만 건드림)
- Produces: `com.kista.trading.application.event`의 6개 신규 이벤트(기존 5개와 합쳐 11개, "event" NamedInterface 공개 범위에 자동 포함) — Task 8의 최종 검증(`ModulithArchitectureTest`)이 `notify↔trading` 순환 해소를 확인하는 대상

**설계 편차 기록**: 스펙 초안은 `TradingErrorEvent(User user, Exception e)`, `InsufficientBalanceEvent(User user, Account account, AccountBalance b, Strategy.Ticker ticker, Strategy.Type strategyType)`를 제시했다. 실제 11개 파일의 호출부를 전수 대조한 결과 이 필드 구성이 정확히 충분함을 확인했다 — 단 한 곳(`TradingService.notifyErrorSafely`)은 admin 알림과 user 알림을 **동시에**(한쪽이 실패해도 다른 쪽은 계속) 호출하므로, 이벤트 발행도 같은 지점에서 `TradingErrorEvent(null, e)`와 `TradingErrorEvent(ctx.user(), e)`를 각각 독립적으로 두 번 발행해 원래의 "개별 try/catch로 격리" 의미론을 그대로 보존한다(아래 Step 5).

**`fallbackExecution=true` 선택 근거**: `TradingReporter`/`CycleRotationService`/`VrCycleRolloverService`/`VrReconfigureService`/`TradingOpenScheduler`/`BatchContextFactory`/`TradingPriceFetcher`/`TradingService`/`ManualTradingService`/`MarketEventNotifier`/`TradingOrderExecutor` 11개 클래스 전부에 `@Transactional`이 없음을 확인했다(`grep -n "@Transactional" <각 파일>` 전부 무결과). Spring의 `@TransactionalEventListener`는 활성 트랜잭션이 없는 상태에서 `phase`만 지정(기본값 `AFTER_COMMIT`)하면 이벤트를 그냥 버린다 — `fallbackExecution = true`가 없으면 이 11개 발행 지점에서 발행한 이벤트는 전부 유실된다. `TradingReportNotifier`/`CycleLifecycleNotifier`가 이미 같은 이유로 `fallbackExecution = true`를 쓰고 있으므로(`CycleEndedNotifier`의 `phase = AFTER_COMMIT` 단독과는 다름), 이번 6개 이벤트도 `TradingAlertNotifier`에서 동일하게 `@TransactionalEventListener(fallbackExecution = true)`(phase 미지정)로 통일한다.

- [ ] **Step 1: 이벤트 레코드 6개 생성**

`src/main/java/com/kista/trading/application/event/TradingErrorEvent.java`:
```java
package com.kista.trading.application.event;

import com.kista.domain.model.user.User;

// 관리자/사용자 매매 오류 알림 — user==null이면 관리자 전용(NotifyPort.notifyError(Exception)),
// non-null이면 사용자 알림(UserNotificationPort.notifyError(User,Exception)). 동일 오류를 관리자+사용자
// 양쪽에 알려야 하는 발행처는 이 이벤트를 두 번(null, 실제 user) 각각 발행한다
public record TradingErrorEvent(User user, Exception e) {}
```

`src/main/java/com/kista/trading/application/event/InsufficientBalanceEvent.java`:
```java
package com.kista.trading.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.trading.domain.model.AccountBalance;

// 예수금 부족 알림 — user==null이면 관리자 알림(NotifyPort.notifyInsufficientBalance(account,b,ticker),
// b 필수/strategyType 미사용), non-null이면 사용자 알림(UserNotificationPort.notifyInsufficientBalance(
// user,account,strategyType,ticker), strategyType 필수/b 미사용) — 두 포트 메서드의 파라미터 합집합을
// 한 이벤트에 담고, 발행처가 쓰지 않는 쪽 필드는 null로 둔다
public record InsufficientBalanceEvent(User user, Account account, AccountBalance b,
                                        Strategy.Ticker ticker, Strategy.Type strategyType) {}
```

`src/main/java/com/kista/trading/application/event/MarketClosedEvent.java`:
```java
package com.kista.trading.application.event;

// 휴장일 — 관리자 알림(NotifyPort.notifyMarketClosed()) 전용, 필드 없음
public record MarketClosedEvent() {}
```

`src/main/java/com/kista/trading/application/event/MarketOpenEvent.java`:
```java
package com.kista.trading.application.event;

import com.kista.domain.model.user.User;

// 사용자별 장 개시 알림 (UserNotificationPort.notifyMarketOpen) — MarketEventNotifier가 ACTIVE 사용자마다 1건씩 발행
public record MarketOpenEvent(User user) {}
```

`src/main/java/com/kista/trading/application/event/MarketCloseEvent.java`:
```java
package com.kista.trading.application.event;

import com.kista.domain.model.user.User;

// 사용자별 장 마감 알림 (UserNotificationPort.notifyMarketClose) — MarketClosedEvent(관리자·휴장 알림)와는 별개 이벤트
public record MarketCloseEvent(User user) {}
```

`src/main/java/com/kista/trading/application/event/BatchInterruptedEvent.java`:
```java
package com.kista.trading.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;

// 스케쥴러 인터럽트(배포·재기동) 사용자 알림 (UserNotificationPort.notifyBatchInterrupted)
public record BatchInterruptedEvent(User user, Account account) {}
```

- [ ] **Step 2: `application/event/package-info.java` 공개 목록 갱신**

`src/main/java/com/kista/trading/application/event/package-info.java`(상위 trading 이전 플랜 Task 4가 만든 untracked 파일 — 아직 커밋 전이므로 그냥 덮어쓴다) 전체를 아래로 교체:
```java
// trading 모듈의 공개 계약 — CycleCompletedEvent/CycleEndedEvent/NewCycleStartedEvent/OrderCancelFailedEvent/
// TradingReportReadyEvent/TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/
// MarketCloseEvent/BatchInterruptedEvent. notify 모듈이 @TransactionalEventListener로 구독한다
// (CLOSED↔CLOSED 모듈 간 이벤트 교차). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.trading.application.event;
```

- [ ] **Step 3: `adapter/in/schedule` 2개 파일 수정 — `TradingOpenScheduler`/`BatchContextFactory`**

`TradingOpenScheduler.java` — import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final NotifyPort notifyPort;            // guardPrivacyStrategies 오류 알림
```
→
```java
    private final ApplicationEventPublisher eventPublisher;  // guardPrivacyStrategies 오류 이벤트 발행
```
호출부:
```java
        notifyPort.notifyError(new IllegalStateException(
                "[PRIVACY] 장전 가드 발동 — 기준 매매표 이상으로 주문 생성 skip: " + report.summary()));
```
→
```java
        eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                "[PRIVACY] 장전 가드 발동 — 기준 매매표 이상으로 주문 생성 skip: " + report.summary())));
```
(admin 전용 — 관리자만 대상이라 `user=null`)

`BatchContextFactory.java` — import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final NotifyPort notifyPort;
```
→
```java
    private final ApplicationEventPublisher eventPublisher;
```
호출부 2곳:
```java
                    notifyPort.notifyError(zombie);
```
→
```java
                    eventPublisher.publishEvent(new TradingErrorEvent(null, zombie));
```
그리고
```java
                notifyPort.notifyError(e);
```
→
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null, e));
```
(`catch (Exception e)` 블록 안, `log.error("[strategyId={}] 컨텍스트 조회 오류: ...")` 다음 줄)

- [ ] **Step 4: Task 5와 겹치는 4개 파일 수정 — `TradingPriceFetcher`/`TradingReporter`/`ManualTradingService`/`TradingOrderExecutor`**

이 4개 파일은 Task 5에서 `place()`/`cancel()`/`getLiveBalance()`/`getPriceSnapshot(s)` 호출부가 이미 broker 소유 타입으로 바뀌어 있다 — 아래 편집은 notify 관련 줄만 건드리므로 그 변경과 무관하게 안전하게 적용된다.

`TradingPriceFetcher.java` — import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final NotifyPort notifyPort; // 일괄+단건 fallback 모두 실패한 종목을 관리자에게 통지
```
→
```java
    private final ApplicationEventPublisher eventPublisher; // 일괄+단건 fallback 모두 실패한 종목을 관리자에게 이벤트로 통지
```
호출부:
```java
            notifyPort.notifyError(new IllegalStateException(
                    failedTickers + " " + label + " 조회 실패(일괄+단건 모두 실패)", lastFailure));
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    failedTickers + " " + label + " 조회 실패(일괄+단건 모두 실패)", lastFailure)));
```

`TradingReporter.java` — `eventPublisher` 필드는 이미 존재(`TradingReportReadyEvent` 발행용) — 새로 추가하지 않는다. import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
```
(`TradingReportReadyEvent` import 바로 아래 줄에 추가). 필드
```java
    private final NotifyPort notifyPort;                            // 취소 실패 등 관리자 알림
```
줄을 삭제(`eventPublisher` 필드는 유지). 호출부:
```java
                    notifyPort.notifyError(e);
```
→
```java
                    eventPublisher.publishEvent(new TradingErrorEvent(null, e));
```

`ManualTradingService.java` — import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final NotifyPort notifyPort; // live 잔고 조회 실패 시 관리자 알림 (4xx라 GlobalExceptionHandler가 미기록)
```
→
```java
    private final ApplicationEventPublisher eventPublisher; // live 잔고 조회 실패 시 관리자 알림 이벤트 (4xx라 GlobalExceptionHandler가 미기록)
```
호출부:
```java
            notifyPort.notifyError(e);
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e));
```
(`fetchLiveBalanceOrThrow`의 catch 블록, `ManualTradingException`으로 rethrow하기 직전 줄)

`TradingOrderExecutor.java` — import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final NotifyPort notifyPort;
```
→
```java
    private final ApplicationEventPublisher eventPublisher;
```
호출부 2곳:
```java
                notifyPort.notifyError(e);
```
(주문 접수 실패 catch 블록, `orderPort.markFailed(p.id());` 바로 앞) →
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null, e));
```
그리고
```java
                notifyPort.notifyError(new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + placedOrder.externalOrderId(), e));
```
→
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + placedOrder.externalOrderId(), e)));
```

- [ ] **Step 5: `TradingService.java` 수정**

import 교체:
```java
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
```
→
```java
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final UserNotificationPort userNotificationPort;   // 사용자 알림 (예수금 부족 등)
```
→
```java
    private final ApplicationEventPublisher eventPublisher;    // 관리자·사용자 알림 이벤트 발행 (오류·휴장·잔고부족)
```

호출부 6곳:

1. `notifyBatchInterrupted(List<BatchContext>)` 내부
```java
                userNotificationPort.notifyBatchInterrupted(ctx.user(), ctx.account());
```
→
```java
                eventPublisher.publishEvent(new BatchInterruptedEvent(ctx.user(), ctx.account()));
```

2. `saveAllocatedOrders` 내부, 예수금 부족 알림
```java
                        userNotificationPort.notifyInsufficientBalance(
                                ctx.user(), ctx.account(), ctx.strategy().type(), ctx.strategy().ticker());
                        return null;
```
→
```java
                        eventPublisher.publishEvent(new InsufficientBalanceEvent(
                                ctx.user(), ctx.account(), null, ctx.strategy().ticker(), ctx.strategy().type()));
                        return null;
```
(user-path 호출이라 `AccountBalance b`는 이 시점에 확보돼 있지 않으므로 `null`로 둔다 — `TradingAlertNotifier`는 `user != null`이면 이 필드를 쓰지 않는다)

3. `waitFor` 내부, 인터럽트 알림
```java
            notifyPort.notifyError(new IllegalStateException(
                    "[스케쥴러 인터럽트] " + label + " 대기 중 강제 종료 — PLANNED 주문 접수 미실행 가능", e));
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "[스케쥴러 인터럽트] " + label + " 대기 중 강제 종료 — PLANNED 주문 접수 미실행 가능", e)));
```

4. `isMarketOpen` 내부, 휴장 알림
```java
            notifyPort.notifyMarketClosed();
```
→
```java
            eventPublisher.publishEvent(new MarketClosedEvent());
```

5~6. `notifyErrorSafely` — admin/user 양쪽을 독립적으로 호출하던 기존 의미론(한쪽 실패가 다른 쪽을 막지 않음)을 보존하기 위해 각 publishEvent를 그대로 개별 try/catch에 유지한다:
```java
    private void notifyErrorSafely(BatchContext ctx, Exception e) {
        try {
            notifyPort.notifyError(e);
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 관리자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
        try {
            userNotificationPort.notifyError(ctx.user(), e);
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 사용자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
    }
```
→
```java
    private void notifyErrorSafely(BatchContext ctx, Exception e) {
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(null, e));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 관리자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user(), e));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 사용자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
    }
```

- [ ] **Step 6: `MarketEventNotifier.java` 수정**

이 클래스는 ACTIVE 사용자마다 `Consumer<User> action`을 virtual thread + 세마포어로 병렬 실행한다(`sendWithLimit`). `eventPublisher.publishEvent(...)`는 동기 호출이라 기존 `action.accept(user)` 자리에 그대로 대체해도 세마포어 점유 시간·예외 격리(`sendWithLimit`의 catch) 의미론이 그대로 유지된다. import 교체:
```java
import com.kista.notify.domain.port.out.UserNotificationPort;
```
→
```java
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import org.springframework.context.ApplicationEventPublisher;
```
필드 교체:
```java
    private final UserNotificationPort userNotificationPort;
```
→
```java
    private final ApplicationEventPublisher eventPublisher;
```
메서드 교체:
```java
    void notifyMarketOpen() {
        notify(NotificationType.MARKET_ALERT, user -> userNotificationPort.notifyMarketOpen(user));
    }

    void notifyMarketClose() {
        notify(NotificationType.MARKET_ALERT, user -> userNotificationPort.notifyMarketClose(user));
    }
```
→
```java
    void notifyMarketOpen() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketOpenEvent(user)));
    }

    void notifyMarketClose() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketCloseEvent(user)));
    }
```

- [ ] **Step 7: `CycleRotationService`/`VrCycleRolloverService`/`VrReconfigureService` 수정**

세 파일 모두 `ApplicationEventPublisher eventPublisher` 필드가 이미 존재한다(`NewCycleStartedEvent` 발행용) — 새로 추가하지 않고 재사용한다.

`CycleRotationService.java` — import
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
줄을 삭제하고
```java
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
```
를 `import com.kista.trading.application.event.NewCycleStartedEvent;` 바로 아래에 추가. 필드
```java
    private final NotifyPort notifyPort;                       // 관리자 알림 (잔고 부족·오류)
```
줄을 삭제. 호출부 3곳:
```java
            notifyPort.notifyInsufficientBalance(account,
                    new AccountBalance(0, null, targetSeed), strategy.ticker());
```
→
```java
            eventPublisher.publishEvent(new InsufficientBalanceEvent(null, account,
                    new AccountBalance(0, null, targetSeed), strategy.ticker(), null));
```
```java
                notifyPort.notifyError(new IllegalStateException("재등록 실패: USD 잔고 없음 strategyId=" + strategy.id()));
```
→
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null,
                        new IllegalStateException("재등록 실패: USD 잔고 없음 strategyId=" + strategy.id())));
```
```java
            notifyPort.notifyError(e);
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e));
```
(`fetchUsdBalance`의 catch 블록)

`VrCycleRolloverService.java` — import
```java
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
```
→
```java
import com.kista.trading.application.event.TradingErrorEvent;
```
필드
```java
    private final NotifyPort notifyPort;                         // 관리자 알림
    private final UserNotificationPort userNotificationPort;     // 사용자 알림 (오류)
```
줄 2개를 삭제. 호출부(전부 `TradingErrorEvent`로 치환 — 관리자용은 `user=null`, 사용자용은 `ctx.user()`):
- `notifyPort.notifyError(e);` (상세 조회 실패) → `eventPublisher.publishEvent(new TradingErrorEvent(null, e));`
- `notifyPort.notifyError(new IllegalStateException("VR 사이클 상세 누락 strategyId=" + strategy.id() + " cycleId=" + cycle.id()));` → `eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException("VR 사이클 상세 누락 strategyId=" + strategy.id() + " cycleId=" + cycle.id())));`
- `notifyPort.notifyError(new IllegalStateException("VR 롤오버 종가 없음 strategyId=" + strategy.id()));` → `eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException("VR 롤오버 종가 없음 strategyId=" + strategy.id())));`
- `notifyPort.notifyError(e);` (due일 확정 종가 조회 실패) → `eventPublisher.publishEvent(new TradingErrorEvent(null, e));`
- `notifyPort.notifyError(new IllegalStateException("VR 롤오버 due일 확정 종가 없음 strategyId=" + strategy.id() + " evaluationDate=" + evaluationDate));` → 동일 패턴으로 `TradingErrorEvent(null, ...)` 래핑
- 인출 반영 후 예수금 음수 블록:
```java
            notifyPort.notifyError(new IllegalStateException(
                    "VR 인출 반영 후 예수금 음수 — 롤오버 보류: strategyId=" + strategy.id() + " adjustedPool=" + adjustedPool));
            userNotificationPort.notifyError(ctx.user(),
                    new IllegalStateException("VR 인출 금액이 예수금을 초과합니다 — 설정 조정 필요: strategyId=" + strategy.id()));
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR 인출 반영 후 예수금 음수 — 롤오버 보류: strategyId=" + strategy.id() + " adjustedPool=" + adjustedPool)));
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user(),
                    new IllegalStateException("VR 인출 금액이 예수금을 초과합니다 — 설정 조정 필요: strategyId=" + strategy.id())));
```
- V′≤0 블록도 동일 패턴:
```java
            notifyPort.notifyError(new IllegalStateException(
                    "VR V′≤0 — 롤오버 보류: strategyId=" + strategy.id() + " newValue=" + newValue));
            userNotificationPort.notifyError(ctx.user(),
                    new IllegalStateException("VR V′≤0 — 설정 조정 필요: strategyId=" + strategy.id()));
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, new IllegalStateException(
                    "VR V′≤0 — 롤오버 보류: strategyId=" + strategy.id() + " newValue=" + newValue)));
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user(),
                    new IllegalStateException("VR V′≤0 — 설정 조정 필요: strategyId=" + strategy.id())));
```

`VrReconfigureService.java` — import
```java
import com.kista.notify.domain.port.out.NotifyPort;
```
줄을 삭제하고
```java
import com.kista.trading.application.event.TradingErrorEvent;
```
를 `import com.kista.trading.application.event.NewCycleStartedEvent;` 아래에 추가. 필드
```java
    private final NotifyPort notifyPort;                     // 사용자 알림 실패 시 관리자 알림
```
줄을 삭제. 호출부
```java
            notifyPort.notifyError(e);
```
→
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e));
```
(주석에 있는 "향후 @Transactional을 붙이면..." 경고는 그대로 유지 — `fallbackExecution=true` 리스너를 쓰는 한 이 경고의 결론은 바뀌지 않는다)

- [ ] **Step 8: `TradingAlertNotifier` 신설 (notify 모듈)**

`src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java`:
```java
package com.kista.notify.adapter.out.gateway;

import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종을 구독해 기존 NotifyPort/UserNotificationPort 메서드를 그대로 호출한다.
// trading의 11개 발행 지점 중 어느 하나도 클래스/메서드에 @Transactional이 없음을 확인했다 — phase=AFTER_COMMIT을
// 단독으로 쓰면 활성 트랜잭션이 없을 때 이벤트가 그냥 버려지므로(TradingReportNotifier/CycleLifecycleNotifier와
// 동일한 이유로) phase 미지정 + fallbackExecution=true로 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다
@Component
@RequiredArgsConstructor
public class TradingAlertNotifier {

    private final NotifyPort notifyPort;                       // 관리자 알림
    private final UserNotificationPort userNotificationPort;   // 사용자 알림

    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingError(TradingErrorEvent event) {
        if (event.user() == null) {
            notifyPort.notifyError(event.e());
        } else {
            userNotificationPort.notifyError(event.user(), event.e());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        if (event.user() == null) {
            notifyPort.notifyInsufficientBalance(event.account(), event.b(), event.ticker());
        } else {
            userNotificationPort.notifyInsufficientBalance(event.user(), event.account(), event.strategyType(), event.ticker());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClosed(MarketClosedEvent event) {
        notifyPort.notifyMarketClosed();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketOpen(MarketOpenEvent event) {
        userNotificationPort.notifyMarketOpen(event.user());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClose(MarketCloseEvent event) {
        userNotificationPort.notifyMarketClose(event.user());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBatchInterrupted(BatchInterruptedEvent event) {
        userNotificationPort.notifyBatchInterrupted(event.user(), event.account());
    }
}
```

- [ ] **Step 9: 영향 테스트 파일 갱신**

| 파일 | 상태 | 변경 내용 |
|---|---|---|
| `adapter/in/schedule/TradingOpenSchedulerTest.java` | 변경 필요(부분) | `@Mock NotifyPort notifyPort`는 **유지**(`SchedulerJobRunner(notifyPort)` 생성용, `TradingOpenScheduler` 자신과 무관). `@Mock ApplicationEventPublisher eventPublisher` 신규 추가. `TradingOpenScheduler` 생성자 호출의 `notifyPort` 인자 위치만 `eventPublisher`로 교체: `new TradingOpenScheduler(useCase, strategyPort, eventPublisher, schedulerLockService, privacyTradePort, validationService, contextFactory, jobRunner, heartbeatPort)`. `run_placeOpenOrdersException_notifiesAdmin`의 `verify(notifyPort).notifyError(ex);`는 **그대로 유지**(SchedulerJobRunner 경로). `run_lockNotAcquired_skipsSchedulerBody`의 `verifyNoInteractions(strategyPort, contextFactory, useCase, notifyPort, heartbeatPort)`도 `notifyPort` 그대로 유지하되 `eventPublisher`를 추가: `verifyNoInteractions(strategyPort, contextFactory, useCase, notifyPort, eventPublisher, heartbeatPort)`. `run_invalidPrivacyBase_pausesPrivacyStrategiesAndSkipsThem`의 `verify(notifyPort).notifyError(any(IllegalStateException.class));`는 guardPrivacyStrategies 자신의 호출이므로 `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && tee.user() == null && tee.e() instanceof IllegalStateException));`로 교체 |
| `adapter/in/schedule/BatchContextFactoryTest.java` | 변경 필요 | `@Mock NotifyPort notifyPort` → `@Mock ApplicationEventPublisher eventPublisher`로 교체, 생성자 호출부 인자 교체. `verify(notifyPort).notifyError(any(NoSuchElementException.class));`/`verify(notifyPort).notifyError(any(IllegalStateException.class));` 2곳을 `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && tee.e() instanceof NoSuchElementException));` 형태(각각 예외 타입에 맞춰)로 교체 |
| `adapter/in/schedule/TradingCloseSchedulerTest.java` | **변경 불필요** | `TradingCloseScheduler.java`는 애초에 11개 파일 목록에 없음 — 이 테스트의 `notifyPort`는 순수하게 `SchedulerJobRunner(notifyPort)` 생성용이라 이번 태스크와 무관. 잘못 손대지 않도록 주의 |
| `application/service/MarketEventNotifierTest.java` | 변경 필요 | `@Mock UserNotificationPort userNotificationPort` → `@Mock ApplicationEventPublisher eventPublisher`, 생성자 인자 교체. `doAnswer(...).when(userNotificationPort).notifyMarketOpen(any());` 류의 스텁·검증을 `doAnswer(...).when(eventPublisher).publishEvent(any());`(이벤트 타입 무관하게 걸리므로 필요 시 `argThat(ev -> ev instanceof MarketOpenEvent)`로 좁힘) 또는 `ArgumentCaptor<MarketOpenEvent>`로 대체. `verify(userNotificationPort, times(userCount)).notifyMarketOpen(any());`류는 `verify(eventPublisher, times(userCount)).publishEvent(any(MarketOpenEvent.class));`로 교체 |
| `application/service/TradingServiceTest.java` | 변경 필요(대규모) | `@Mock NotifyPort notifyPort`/`@Mock UserNotificationPort userNotificationPort` 2개 필드를 제거하고 기존 `@Mock ApplicationEventPublisher eventPublisher`(이미 존재)를 재사용. `TradingService` 생성자 호출부(`notifyPort, eventPublisher, ...` 위치)에서 `notifyPort` 인자를 제거. 단, 이 목 객체들은 `TradingPriceFetcher`/`TradingOrderExecutor`/`MarketEventNotifier` 등 **헬퍼 컴포넌트를 직접 new하는 데도** 재사용되고 있으므로(예: `new TradingPriceFetcher(tradingRegistry, notifyPort)`) 그 헬퍼들 생성자 호출부도 Step 4/6에서 바뀐 시그니처(`eventPublisher`)에 맞춰 함께 고친다. `verify(notifyPort).notifyMarketClosed();` → `verify(eventPublisher).publishEvent(new MarketClosedEvent());`. `verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));` → `verify(eventPublisher).publishEvent(new InsufficientBalanceEvent(USER, ACCOUNT, null, Ticker.SOXL, Strategy.Type.INFINITE));`. `verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), any(AccountBalance.class), eq(Ticker.SOXL));`(admin-path)는 `AccountBalance` 실제 값을 캡처해야 하므로 `ArgumentCaptor<InsufficientBalanceEvent>`로 캡처 후 `captor.getValue().b()`를 단언. `verify(notifyPort).notifyError(...)`류 전부 `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && tee.user()==null && ...))`로, `verify(userNotificationPort).notifyBatchInterrupted(USER, ACCOUNT);` → `verify(eventPublisher).publishEvent(new BatchInterruptedEvent(USER, ACCOUNT));`. 기존 `verify(eventPublisher).publishEvent(argThat(...))` 패턴(`TradingReportReadyEvent`/`NewCycleStartedEvent` 검증)과 동일한 스타일로 통일 |
| `application/service/TradingOrderExecutorTest.java` | 변경 필요 | `@Mock NotifyPort notifyPort` → `@Mock ApplicationEventPublisher eventPublisher`, 생성자 인자 교체. `verify(notifyPort, never()).notifyError(any());` → `verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));`. `verify(notifyPort).notifyError(any(IllegalStateException.class));` → `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && tee.e() instanceof IllegalStateException));` |
| `application/service/VrCycleRolloverServiceTest.java` | 변경 필요 | `@Mock NotifyPort notifyPort`/`@Mock UserNotificationPort userNotificationPort` 제거, 기존 `@Mock ApplicationEventPublisher eventPublisher` 재사용. 생성자 호출부에서 두 인자 제거. `verify(notifyPort).notifyError(any(IllegalStateException.class)); verify(userNotificationPort).notifyError(eq(USER), any(IllegalStateException.class));` 쌍은 `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && tee.user()==null)); verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && USER.equals(tee.user())));` 쌍으로 교체(관리자용/사용자용 각각). `verify(userNotificationPort, never()).notifyError(...)` → `verify(eventPublisher, never()).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent tee && USER.equals(tee.user())));`. `verify(eventPublisher).publishEvent(new NewCycleStartedEvent(...))` 검증은 무변경 |
| `application/service/VrReconfigureServiceTest.java` | 변경 필요(소규모) | `@Mock NotifyPort notifyPort` 제거, 기존 `@Mock ApplicationEventPublisher eventPublisher` 재사용. 생성자 호출부에서 `notifyPort` 인자 제거. `verify(notifyPort).notifyError(any());` → `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent));`(기존 `.when(eventPublisher).publishEvent(any())`로 예외를 던지게 해 이 알림 실패 경로를 재현하는 스텁도 그대로 둔다) |
| `application/service/CycleRotationServiceTest.java` | 변경 필요 | `@Mock NotifyPort notifyPort` 제거, 기존 `@Mock ApplicationEventPublisher eventPublisher` 재사용. 생성자 호출부에서 `notifyPort` 인자 제거. `verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());` → `verify(eventPublisher, never()).publishEvent(any(InsufficientBalanceEvent.class));`. `verify(notifyPort).notifyInsufficientBalance(eq(ACCOUNT), ...);` → `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof InsufficientBalanceEvent ibe && ACCOUNT.equals(ibe.account())));`(필요 시 `ArgumentCaptor`로 `b()` 값까지 단언). `verify(notifyPort).notifyError(...)` 2곳 → `verify(eventPublisher).publishEvent(argThat(ev -> ev instanceof TradingErrorEvent));` |
| `application/service/ManualTradingServiceTest.java` | 변경 필요(소규모) | `@Mock NotifyPort notifyPort` → `@Mock ApplicationEventPublisher eventPublisher`. `new TradingPriceFetcher(brokerAdapterRegistry, notifyPort)` 호출부도 Step 4에서 바뀐 시그니처(`eventPublisher`)로 함께 교체(같은 목 재사용). `ManualTradingService` 생성자 호출부의 `notifyPort` 인자도 `eventPublisher`로 교체. `verify(notifyPort).notifyError(any());` → `verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));` |
| `application/service/TradingReporterTest.java` | 변경 필요(소규모) | `@Mock NotifyPort notifyPort` 제거, 기존 `@Mock ApplicationEventPublisher eventPublisher` 재사용. 생성자 호출부에서 `notifyPort` 인자 제거. `verify(notifyPort).notifyError(any());` → `verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));`. `verify(notifyPort, never()).notifyError(any());` → `verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));`(기존 `TradingReportReadyEvent` 캡처 검증과 공존하므로 `any(TradingErrorEvent.class)`로 타입을 좁혀야 오검출 없음) |

- [ ] **Step 10: `TradingAlertNotifierTest` 신설 (notify 모듈, `CycleEndedNotifierTest` 패턴)**

`src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java`:
```java
package com.kista.notify.adapter.out.gateway;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.notify.domain.port.out.NotifyPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import com.kista.support.DomainFixtures;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.domain.model.AccountBalance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// trading이 발행하는 관리자/사용자 알림 이벤트 6종이 기존 NotifyPort/UserNotificationPort 메서드로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class TradingAlertNotifierTest {

    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;

    private final UUID userId = UUID.randomUUID();
    private final Account account = DomainFixtures.kisAccount(UUID.randomUUID(), userId);
    private final User user = DomainFixtures.activeUserWithTelegram(userId);

    private TradingAlertNotifier notifier() {
        return new TradingAlertNotifier(notifyPort, userNotificationPort);
    }

    @Test
    void onTradingError_adminPath_callsNotifyPortWhenUserIsNull() {
        Exception e = new IllegalStateException("배치 오류");

        notifier().onTradingError(new TradingErrorEvent(null, e));

        verify(notifyPort).notifyError(e);
        verify(userNotificationPort, never()).notifyError(any(), any());
    }

    @Test
    void onTradingError_userPath_callsUserNotificationPortWhenUserPresent() {
        Exception e = new IllegalStateException("사용자 매매 오류");

        notifier().onTradingError(new TradingErrorEvent(user, e));

        verify(userNotificationPort).notifyError(user, e);
        verify(notifyPort, never()).notifyError(any());
    }

    @Test
    void onInsufficientBalance_adminPath_callsNotifyPortWithAccountBalance() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("100.00"));

        notifier().onInsufficientBalance(new InsufficientBalanceEvent(null, account, balance, Ticker.SOXL, null));

        verify(notifyPort).notifyInsufficientBalance(account, balance, Ticker.SOXL);
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void onInsufficientBalance_userPath_callsUserNotificationPortWithStrategyType() {
        notifier().onInsufficientBalance(
                new InsufficientBalanceEvent(user, account, null, Ticker.SOXL, Strategy.Type.INFINITE));

        verify(userNotificationPort).notifyInsufficientBalance(user, account, Strategy.Type.INFINITE, Ticker.SOXL);
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }

    @Test
    void onMarketClosed_callsNotifyPort() {
        notifier().onMarketClosed(new MarketClosedEvent());

        verify(notifyPort).notifyMarketClosed();
    }

    @Test
    void onMarketOpen_callsUserNotificationPort() {
        notifier().onMarketOpen(new MarketOpenEvent(user));

        verify(userNotificationPort).notifyMarketOpen(user);
    }

    @Test
    void onMarketClose_callsUserNotificationPort() {
        notifier().onMarketClose(new MarketCloseEvent(user));

        verify(userNotificationPort).notifyMarketClose(user);
    }

    @Test
    void onBatchInterrupted_callsUserNotificationPort() {
        notifier().onBatchInterrupted(new BatchInterruptedEvent(user, account));

        verify(userNotificationPort).notifyBatchInterrupted(user, account);
    }
}
```

**주의**: `DomainFixtures.kisAccount`/`DomainFixtures.activeUserWithTelegram` 헬퍼가 실제로 그 이름·시그니처로 `com.kista.support.DomainFixtures`에 존재하는지 착수 직전 확인할 것 — 없거나 이름이 다르면 그 파일의 실제 헬퍼(또는 `CycleEndedNotifierTest`가 쓰는 fixture 생성 방식)로 맞춰 쓴다.

- [ ] **Step 11: compileJava/compileTestJava 실행**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
./gradlew compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: 둘 다 `BUILD SUCCESSFUL`. `cannot find symbol: notifyPort`류 에러가 남아있으면 Step 3~7 중 해당 파일의 필드/호출부 치환이 누락된 것이니 재확인한다.

- [ ] **Step 12: 타겟 테스트 실행**

```bash
./gradlew test \
  --tests 'com.kista.trading.adapter.in.schedule.*' \
  --tests 'com.kista.trading.application.service.*' \
  --tests 'com.kista.notify.adapter.out.gateway.*' \
  --tests 'com.kista.architecture.*'
```
Expected: 전부 PASS, 특히 `ModulithArchitectureTest`의 3개 순환(`broker↔trading`, `broker→trading→notify→broker`, `notify→trading→notify`)이 모두 사라져야 한다(Task 6까지는 2개만 해소됨). 실패 시 `docs/agents/commands.md`의 XML 기반 진단 절차 사용.

- [ ] **Step 13: 커밋**

```bash
git add src/main/java/com/kista/trading/application/event/ \
        src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java \
        src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java \
        src/main/java/com/kista/trading/adapter/in/schedule/BatchContextFactory.java \
        src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java \
        src/main/java/com/kista/trading/application/service/TradingReporter.java \
        src/main/java/com/kista/trading/application/service/ManualTradingService.java \
        src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/main/java/com/kista/trading/application/service/MarketEventNotifier.java \
        src/main/java/com/kista/trading/application/service/CycleRotationService.java \
        src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java \
        src/main/java/com/kista/trading/application/service/VrReconfigureService.java \
        src/test/java/com/kista/trading/adapter/in/schedule/TradingOpenSchedulerTest.java \
        src/test/java/com/kista/trading/adapter/in/schedule/BatchContextFactoryTest.java \
        src/test/java/com/kista/trading/application/service/MarketEventNotifierTest.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java \
        src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java \
        src/test/java/com/kista/trading/application/service/VrCycleRolloverServiceTest.java \
        src/test/java/com/kista/trading/application/service/VrReconfigureServiceTest.java \
        src/test/java/com/kista/trading/application/service/CycleRotationServiceTest.java \
        src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java \
        src/test/java/com/kista/trading/application/service/TradingReporterTest.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): trading→notify 직접 호출 11곳을 이벤트 발행으로 전환

trading의 11개 파일이 NotifyPort/UserNotificationPort를 직접 주입해 호출하던 것을 신규 이벤트 6종
(TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/MarketCloseEvent/
BatchInterruptedEvent) 발행으로 전환하고, notify 모듈의 TradingAlertNotifier가 이를 구독해 기존
NotifyPort/UserNotificationPort 메서드를 그대로 호출한다. 11개 발행 지점 전부 @Transactional이 없어
리스너는 fallbackExecution=true로 구성했다(단독 AFTER_COMMIT은 트랜잭션 없는 호출 경로에서 이벤트를
버림). NotifyPort.notifyInsufficientBalance/UserNotificationPort.notifyTradingReport가 trading의
AccountBalance/TradingReport를 시그니처에 갖는 notify→trading 참조는 단방향이라 그대로 유지 — 이번
변경으로 trading→notify 호출이 전부 사라지면서 notify↔trading 순환이 해소된다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

---

### Task 8: 최종 검증 + 상위 trading 이전 플랜 Task 4 재개 + 문서 갱신

**Files:**
- Modify(재개): 상위 플랜(`docs/superpowers/plans/2026-08-29-spring-modulith-trading-migration.md`) Task 4의 산출물 — worktree에 이미 untracked 상태로 존재하는 7개 `package-info.java` 파일(커밋 안 됨, `.superpowers/sdd/2026-08-29-spring-modulith-trading-migration/task-4-report.md` 참고)
- Modify: `docs/agents/architecture.md`, `docs/agents/constraints.md`

**Interfaces:**
- Consumes: Task 1~7 전체 — 이 태스크가 순환 제거의 최종 검증 지점

- [ ] **Step 1: 전체 컴파일 확인**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다. 에러 있으면 Task 1~7 중 어느 파일이 놓쳤는지 특정해 수정.

- [ ] **Step 2: broker/notify/trading/mock 전체 테스트**

```bash
./gradlew test --tests 'com.kista.broker.*' --tests 'com.kista.notify.*' --tests 'com.kista.trading.*' \
  --tests 'com.kista.application.service.admin.*' --tests 'com.kista.application.service.strategy.*' \
  --tests 'com.kista.domain.backtest.*' --tests 'com.kista.adapter.out.marketdata.*'
```
Expected: 전부 PASS.

- [ ] **Step 3: `ModulithArchitectureTest` — 이 작업 전체의 최종 판정**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS, 순환 0건. 실패하면 실패 메시지의 `Cycle detected` 블록을 읽고 어느 파일이 아직 trading/broker/notify 타입을 잘못된 방향으로 참조하는지 특정 — Task 1~7이 놓친 파일이 있다는 뜻이므로 해당 태스크로 돌아가 수정(새 예외를 만들지 말 것).

- [ ] **Step 4: `HexagonalArchitectureTest` 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS.

- [ ] **Step 5: 상위 플랜 Task 4 재개 — package-info.java 7개 커밋**

Step 3가 PASS했다면 worktree에 남아있던 untracked 7개 `package-info.java`(상위 플랜 Task 4의 산출물, 컴파일은 되지만 그때는 순환 때문에 테스트가 막혀 있었음)를 그대로 커밋한다:
```bash
git status --short src/main/java/com/kista/trading/package-info.java \
        src/main/java/com/kista/trading/domain/model/package-info.java \
        src/main/java/com/kista/trading/domain/strategy/package-info.java \
        src/main/java/com/kista/trading/domain/port/in/package-info.java \
        src/main/java/com/kista/trading/domain/port/out/package-info.java \
        src/main/java/com/kista/trading/application/event/package-info.java \
        src/main/java/com/kista/trading/adapter/in/schedule/package-info.java
```
7개 다 `??`(untracked)로 나오면 내용이 상위 플랜 Task 4 브리핑(`.superpowers/sdd/2026-08-29-spring-modulith-trading-migration/task-4-brief.md`) Step 1~3.5와 일치하는지 대조 후 그대로 커밋:
```bash
git add src/main/java/com/kista/trading/package-info.java \
        src/main/java/com/kista/trading/domain/model/package-info.java \
        src/main/java/com/kista/trading/domain/strategy/package-info.java \
        src/main/java/com/kista/trading/domain/port/in/package-info.java \
        src/main/java/com/kista/trading/domain/port/out/package-info.java \
        src/main/java/com/kista/trading/application/event/package-info.java \
        src/main/java/com/kista/trading/adapter/in/schedule/package-info.java
git commit -m "$(cat <<'EOF'
feat(modulith): trading 모듈 선언 — CLOSED + domain·event·schedule 3개 Named Interface 공개

broker↔trading, notify↔trading, broker→trading→notify→broker 3개 순환을
com.kista.trading-broker-notify-decoupling 작업(별도 스펙/플랜)으로 제거한
뒤 재개. domain.model/domain.strategy/domain.port.{in,out}을 "domain"으로,
application.event를 "event"로, adapter.in.schedule을 "schedule"로
NamedInterface 공개한다. application.service·adapter.out.*은 미공개.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

- [ ] **Step 6: architecture.md/constraints.md에 이번 작업 반영**

`docs/agents/architecture.md`의 `com.kista.broker/` 섹션에 신규 타입(`Direction, OrderType, PriceSnapshot, BrokerBalance, OrderInstruction, OrderResult, CancelInstruction, MockSimulationDataPort`) 추가 서술, `com.kista.notify/` 섹션에 `TradingAlertNotifier`(또는 실제 채택한 리스너 클래스명 — Task 7 결과 확인) 추가. `docs/agents/constraints.md`에 "broker/notify 포트는 trading 타입을 시그니처에 직접 참조하지 않는다 — 모듈 경계상 자기 소유 타입만 사용, trading이 매핑" 원칙을 새 항목으로 추가.

- [ ] **Step 7: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: 커밋**

```bash
git add docs/agents/architecture.md docs/agents/constraints.md
git commit -m "$(cat <<'EOF'
docs(modulith): broker↔trading/notify 디커플링 반영 — architecture/constraints 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점을 발견하면:
1. **임의로 고치지 않는다** — 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고
3. 스펙 문서의 "리팩토링 관찰" 섹션에 기록(사용자 승인 시)

이미 이 계획에 반영된 항목(`kisSllType()` 이동, `Execution.Fill` 제거, `DstInfo` 부분 복제)은 예외 — 브레인스토밍 단계에서 이미 승인됨.
