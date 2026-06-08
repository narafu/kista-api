# application/service/ 도메인별 재조직 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `application/service/` 패키지의 30개 클래스를 8개 도메인 서브패키지(user/account/tradingcycle/trading/privacy/portfolio/market/admin)로 이동한다.

**Architecture:** 각 파일의 `package` 선언만 변경하고 파일을 해당 서브디렉토리로 이동한다. 도메인 간 직접 의존은 `AdminService → UserCascadeDeleter` 하나뿐이며, `UserCascadeDeleter`를 `public`으로 변경하고 import를 추가하는 것으로 해결한다.

**Tech Stack:** Java 21, Spring Boot 3, Gradle, JUnit 5, Mockito

---

## 파일 변경 매핑

### main/java/com/kista/application/service/

| 현재 파일 | 이동 후 경로 | 변경 사항 |
|---|---|---|
| `UserService.java` | `user/UserService.java` | package 선언만 |
| `UserCascadeDeleter.java` | `user/UserCascadeDeleter.java` | package 선언 + **`public` 추가** |
| `FcmTokenService.java` | `user/FcmTokenService.java` | package 선언만 |
| `KakaoLoginService.java` | `user/KakaoLoginService.java` | package 선언만 |
| `AccountService.java` | `account/AccountService.java` | package 선언만 |
| `AccountStatisticsService.java` | `account/AccountStatisticsService.java` | package 선언만 |
| `KisConnectionTestService.java` | `account/KisConnectionTestService.java` | package 선언만 |
| `TradingCycleService.java` | `tradingcycle/TradingCycleService.java` | package 선언만 |
| `TradingService.java` | `trading/TradingService.java` | package 선언만 |
| `ManualTradingService.java` | `trading/ManualTradingService.java` | package 선언만 |
| `TradingPreviewService.java` | `trading/TradingPreviewService.java` | package 선언만 |
| `TradingOrderExecutor.java` | `trading/TradingOrderExecutor.java` | package 선언만 |
| `TradingOrderPlanner.java` | `trading/TradingOrderPlanner.java` | package 선언만 |
| `TradingBalanceLoader.java` | `trading/TradingBalanceLoader.java` | package 선언만 |
| `TradingPriceFetcher.java` | `trading/TradingPriceFetcher.java` | package 선언만 |
| `TradingReporter.java` | `trading/TradingReporter.java` | package 선언만 |
| `CycleOrderComputer.java` | `trading/CycleOrderComputer.java` | package 선언만 |
| `CycleRotationService.java` | `trading/CycleRotationService.java` | package 선언만 |
| `BuyOrderPriceCapper.java` | `trading/BuyOrderPriceCapper.java` | package 선언만 |
| `OrderCancelService.java` | `trading/OrderCancelService.java` | package 선언만 |
| `PrivacyTradeService.java` | `privacy/PrivacyTradeService.java` | package 선언만 |
| `FidaOrderService.java` | `privacy/FidaOrderService.java` | package 선언만 |
| `PortfolioService.java` | `portfolio/PortfolioService.java` | package 선언만 |
| `TradeHistoryService.java` | `portfolio/TradeHistoryService.java` | package 선언만 |
| `MarketHolidayService.java` | `market/MarketHolidayService.java` | package 선언만 |
| `AdminService.java` | `admin/AdminService.java` | package 선언 + **import 추가** |
| `AdminAccountService.java` | `admin/AdminAccountService.java` | package 선언만 |
| `AdminAnomaliesService.java` | `admin/AdminAnomaliesService.java` | package 선언만 |
| `AdminAuditService.java` | `admin/AdminAuditService.java` | package 선언만 |
| `AdminTradeService.java` | `admin/AdminTradeService.java` | package 선언만 |

### test/java/com/kista/application/service/

| 현재 파일 | 이동 후 경로 | 변경 사항 |
|---|---|---|
| `UserServiceTest.java` | `user/UserServiceTest.java` | package 선언만 |
| `FcmTokenServiceTest.java` | `user/FcmTokenServiceTest.java` | package 선언만 |
| `AccountServiceTest.java` | `account/AccountServiceTest.java` | package 선언만 |
| `TradingCycleServiceTest.java` | `tradingcycle/TradingCycleServiceTest.java` | package 선언만 |
| `TradingServiceTest.java` | `trading/TradingServiceTest.java` | package 선언만 |
| `TradingPreviewServiceTest.java` | `trading/TradingPreviewServiceTest.java` | package 선언만 |
| `TradingOrderExecutorTest.java` | `trading/TradingOrderExecutorTest.java` | package 선언만 |
| `TradingOrderPlannerTest.java` | `trading/TradingOrderPlannerTest.java` | package 선언만 |
| `BuyOrderPriceCapperTest.java` | `trading/BuyOrderPriceCapperTest.java` | package 선언만 |
| `CycleRotationServiceTest.java` | `trading/CycleRotationServiceTest.java` | package 선언만 |
| `OrderCancelServiceTest.java` | `trading/OrderCancelServiceTest.java` | package 선언만 |
| `FidaOrderServiceTest.java` | `privacy/FidaOrderServiceTest.java` | package 선언만 |
| `PortfolioServiceTest.java` | `portfolio/PortfolioServiceTest.java` | package 선언만 |
| `AdminServiceTest.java` | `admin/AdminServiceTest.java` | package 선언 + **import 추가** |

---

## 기준 경로

```
SRC=src/main/java/com/kista/application/service
TST=src/test/java/com/kista/application/service
```

---

## Task 0: 베이스라인 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 현재 테스트 전체 통과 확인**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test --tests 'com.kista.application.service.*' --rerun-tasks
```

Expected: BUILD SUCCESSFUL, 모든 테스트 PASSED

---

## Task 1: user/ 패키지 이동

**Files:**
- Modify: `src/main/java/com/kista/application/service/UserService.java`
- Modify: `src/main/java/com/kista/application/service/UserCascadeDeleter.java`
- Modify: `src/main/java/com/kista/application/service/FcmTokenService.java`
- Modify: `src/main/java/com/kista/application/service/KakaoLoginService.java`
- Modify: `src/test/java/com/kista/application/service/UserServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/FcmTokenServiceTest.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
cd /Users/phs/workspace/kista/kista-api
mkdir -p src/main/java/com/kista/application/service/user
mkdir -p src/test/java/com/kista/application/service/user

git mv src/main/java/com/kista/application/service/UserService.java \
       src/main/java/com/kista/application/service/user/UserService.java
git mv src/main/java/com/kista/application/service/UserCascadeDeleter.java \
       src/main/java/com/kista/application/service/user/UserCascadeDeleter.java
git mv src/main/java/com/kista/application/service/FcmTokenService.java \
       src/main/java/com/kista/application/service/user/FcmTokenService.java
git mv src/main/java/com/kista/application/service/KakaoLoginService.java \
       src/main/java/com/kista/application/service/user/KakaoLoginService.java

git mv src/test/java/com/kista/application/service/UserServiceTest.java \
       src/test/java/com/kista/application/service/user/UserServiceTest.java
git mv src/test/java/com/kista/application/service/FcmTokenServiceTest.java \
       src/test/java/com/kista/application/service/user/FcmTokenServiceTest.java
```

- [ ] **Step 2: UserService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/user/UserService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.user;`

- [ ] **Step 3: UserCascadeDeleter.java — package 선언 변경 + public 추가**

파일: `src/main/java/com/kista/application/service/user/UserCascadeDeleter.java`

변경 전:
```java
package com.kista.application.service;
...
@Component
@RequiredArgsConstructor
class UserCascadeDeleter {
```

변경 후:
```java
package com.kista.application.service.user;
...
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {
```

- [ ] **Step 4: FcmTokenService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/user/FcmTokenService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.user;`

- [ ] **Step 5: KakaoLoginService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/user/KakaoLoginService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.user;`

- [ ] **Step 6: UserServiceTest.java — package 선언 변경**

파일: `src/test/java/com/kista/application/service/user/UserServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.user;`

- [ ] **Step 7: FcmTokenServiceTest.java — package 선언 변경**

파일: `src/test/java/com/kista/application/service/user/FcmTokenServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.user;`

- [ ] **Step 8: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/user 패키지 분리"
```

---

## Task 2: account/ 패키지 이동

**Files:**
- Modify: `src/main/java/com/kista/application/service/AccountService.java`
- Modify: `src/main/java/com/kista/application/service/AccountStatisticsService.java`
- Modify: `src/main/java/com/kista/application/service/KisConnectionTestService.java`
- Modify: `src/test/java/com/kista/application/service/AccountServiceTest.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/account
mkdir -p src/test/java/com/kista/application/service/account

git mv src/main/java/com/kista/application/service/AccountService.java \
       src/main/java/com/kista/application/service/account/AccountService.java
git mv src/main/java/com/kista/application/service/AccountStatisticsService.java \
       src/main/java/com/kista/application/service/account/AccountStatisticsService.java
git mv src/main/java/com/kista/application/service/KisConnectionTestService.java \
       src/main/java/com/kista/application/service/account/KisConnectionTestService.java

git mv src/test/java/com/kista/application/service/AccountServiceTest.java \
       src/test/java/com/kista/application/service/account/AccountServiceTest.java
```

- [ ] **Step 2: AccountService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/account/AccountService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.account;`

- [ ] **Step 3: AccountStatisticsService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/account/AccountStatisticsService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.account;`

- [ ] **Step 4: KisConnectionTestService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/account/KisConnectionTestService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.account;`

- [ ] **Step 5: AccountServiceTest.java — package 선언 변경**

파일: `src/test/java/com/kista/application/service/account/AccountServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.account;`

- [ ] **Step 6: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/account 패키지 분리"
```

---

## Task 3: tradingcycle/ 패키지 이동

**Files:**
- Modify: `src/main/java/com/kista/application/service/TradingCycleService.java`
- Modify: `src/test/java/com/kista/application/service/TradingCycleServiceTest.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/tradingcycle
mkdir -p src/test/java/com/kista/application/service/tradingcycle

git mv src/main/java/com/kista/application/service/TradingCycleService.java \
       src/main/java/com/kista/application/service/tradingcycle/TradingCycleService.java

git mv src/test/java/com/kista/application/service/TradingCycleServiceTest.java \
       src/test/java/com/kista/application/service/tradingcycle/TradingCycleServiceTest.java
```

- [ ] **Step 2: TradingCycleService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/tradingcycle/TradingCycleService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.tradingcycle;`

- [ ] **Step 3: TradingCycleServiceTest.java — package 선언 변경**

파일: `src/test/java/com/kista/application/service/tradingcycle/TradingCycleServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.tradingcycle;`

- [ ] **Step 4: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/tradingcycle 패키지 분리"
```

---

## Task 4: trading/ 패키지 이동

**Files (source 12개, test 7개):**
- `TradingService`, `ManualTradingService`, `TradingPreviewService`, `TradingOrderExecutor`, `TradingOrderPlanner`, `TradingBalanceLoader`, `TradingPriceFetcher`, `TradingReporter`, `CycleOrderComputer`, `CycleRotationService`, `BuyOrderPriceCapper`, `OrderCancelService`
- Tests: `TradingServiceTest`, `TradingPreviewServiceTest`, `TradingOrderExecutorTest`, `TradingOrderPlannerTest`, `BuyOrderPriceCapperTest`, `CycleRotationServiceTest`, `OrderCancelServiceTest`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/trading
mkdir -p src/test/java/com/kista/application/service/trading

git mv src/main/java/com/kista/application/service/TradingService.java \
       src/main/java/com/kista/application/service/trading/TradingService.java
git mv src/main/java/com/kista/application/service/ManualTradingService.java \
       src/main/java/com/kista/application/service/trading/ManualTradingService.java
git mv src/main/java/com/kista/application/service/TradingPreviewService.java \
       src/main/java/com/kista/application/service/trading/TradingPreviewService.java
git mv src/main/java/com/kista/application/service/TradingOrderExecutor.java \
       src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java
git mv src/main/java/com/kista/application/service/TradingOrderPlanner.java \
       src/main/java/com/kista/application/service/trading/TradingOrderPlanner.java
git mv src/main/java/com/kista/application/service/TradingBalanceLoader.java \
       src/main/java/com/kista/application/service/trading/TradingBalanceLoader.java
git mv src/main/java/com/kista/application/service/TradingPriceFetcher.java \
       src/main/java/com/kista/application/service/trading/TradingPriceFetcher.java
git mv src/main/java/com/kista/application/service/TradingReporter.java \
       src/main/java/com/kista/application/service/trading/TradingReporter.java
git mv src/main/java/com/kista/application/service/CycleOrderComputer.java \
       src/main/java/com/kista/application/service/trading/CycleOrderComputer.java
git mv src/main/java/com/kista/application/service/CycleRotationService.java \
       src/main/java/com/kista/application/service/trading/CycleRotationService.java
git mv src/main/java/com/kista/application/service/BuyOrderPriceCapper.java \
       src/main/java/com/kista/application/service/trading/BuyOrderPriceCapper.java
git mv src/main/java/com/kista/application/service/OrderCancelService.java \
       src/main/java/com/kista/application/service/trading/OrderCancelService.java

git mv src/test/java/com/kista/application/service/TradingServiceTest.java \
       src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git mv src/test/java/com/kista/application/service/TradingPreviewServiceTest.java \
       src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java
git mv src/test/java/com/kista/application/service/TradingOrderExecutorTest.java \
       src/test/java/com/kista/application/service/trading/TradingOrderExecutorTest.java
git mv src/test/java/com/kista/application/service/TradingOrderPlannerTest.java \
       src/test/java/com/kista/application/service/trading/TradingOrderPlannerTest.java
git mv src/test/java/com/kista/application/service/BuyOrderPriceCapperTest.java \
       src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java
git mv src/test/java/com/kista/application/service/CycleRotationServiceTest.java \
       src/test/java/com/kista/application/service/trading/CycleRotationServiceTest.java
git mv src/test/java/com/kista/application/service/OrderCancelServiceTest.java \
       src/test/java/com/kista/application/service/trading/OrderCancelServiceTest.java
```

- [ ] **Step 2: 소스 12개 — package 선언 변경**

아래 파일들의 첫 번째 줄을 `package com.kista.application.service;` → `package com.kista.application.service.trading;`으로 변경한다.

```
src/main/java/com/kista/application/service/trading/TradingService.java
src/main/java/com/kista/application/service/trading/ManualTradingService.java
src/main/java/com/kista/application/service/trading/TradingPreviewService.java
src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java
src/main/java/com/kista/application/service/trading/TradingOrderPlanner.java
src/main/java/com/kista/application/service/trading/TradingBalanceLoader.java
src/main/java/com/kista/application/service/trading/TradingPriceFetcher.java
src/main/java/com/kista/application/service/trading/TradingReporter.java
src/main/java/com/kista/application/service/trading/CycleOrderComputer.java
src/main/java/com/kista/application/service/trading/CycleRotationService.java
src/main/java/com/kista/application/service/trading/BuyOrderPriceCapper.java
src/main/java/com/kista/application/service/trading/OrderCancelService.java
```

- [ ] **Step 3: 테스트 7개 — package 선언 변경**

아래 파일들의 첫 번째 줄을 `package com.kista.application.service;` → `package com.kista.application.service.trading;`으로 변경한다.

```
src/test/java/com/kista/application/service/trading/TradingServiceTest.java
src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java
src/test/java/com/kista/application/service/trading/TradingOrderExecutorTest.java
src/test/java/com/kista/application/service/trading/TradingOrderPlannerTest.java
src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java
src/test/java/com/kista/application/service/trading/CycleRotationServiceTest.java
src/test/java/com/kista/application/service/trading/OrderCancelServiceTest.java
```

- [ ] **Step 4: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/trading 패키지 분리"
```

---

## Task 5: privacy/ 패키지 이동

**Files:**
- Modify: `src/main/java/com/kista/application/service/PrivacyTradeService.java`
- Modify: `src/main/java/com/kista/application/service/FidaOrderService.java`
- Modify: `src/test/java/com/kista/application/service/FidaOrderServiceTest.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/privacy
mkdir -p src/test/java/com/kista/application/service/privacy

git mv src/main/java/com/kista/application/service/PrivacyTradeService.java \
       src/main/java/com/kista/application/service/privacy/PrivacyTradeService.java
git mv src/main/java/com/kista/application/service/FidaOrderService.java \
       src/main/java/com/kista/application/service/privacy/FidaOrderService.java

git mv src/test/java/com/kista/application/service/FidaOrderServiceTest.java \
       src/test/java/com/kista/application/service/privacy/FidaOrderServiceTest.java
```

- [ ] **Step 2: PrivacyTradeService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/privacy/PrivacyTradeService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.privacy;`

- [ ] **Step 3: FidaOrderService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/privacy/FidaOrderService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.privacy;`

- [ ] **Step 4: FidaOrderServiceTest.java — package 선언 변경**

파일: `src/test/java/com/kista/application/service/privacy/FidaOrderServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.privacy;`

- [ ] **Step 5: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/privacy 패키지 분리"
```

---

## Task 6: portfolio/ 패키지 이동

**Files:**
- Modify: `src/main/java/com/kista/application/service/PortfolioService.java`
- Modify: `src/main/java/com/kista/application/service/TradeHistoryService.java`
- Modify: `src/test/java/com/kista/application/service/PortfolioServiceTest.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/portfolio
mkdir -p src/test/java/com/kista/application/service/portfolio

git mv src/main/java/com/kista/application/service/PortfolioService.java \
       src/main/java/com/kista/application/service/portfolio/PortfolioService.java
git mv src/main/java/com/kista/application/service/TradeHistoryService.java \
       src/main/java/com/kista/application/service/portfolio/TradeHistoryService.java

git mv src/test/java/com/kista/application/service/PortfolioServiceTest.java \
       src/test/java/com/kista/application/service/portfolio/PortfolioServiceTest.java
```

- [ ] **Step 2: PortfolioService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/portfolio/PortfolioService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.portfolio;`

- [ ] **Step 3: TradeHistoryService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/portfolio/TradeHistoryService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.portfolio;`

- [ ] **Step 4: PortfolioServiceTest.java — package 선언 변경**

파일: `src/test/java/com/kista/application/service/portfolio/PortfolioServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.portfolio;`

- [ ] **Step 5: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/portfolio 패키지 분리"
```

---

## Task 7: market/ 패키지 이동

**Files:**
- Modify: `src/main/java/com/kista/application/service/MarketHolidayService.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/market

git mv src/main/java/com/kista/application/service/MarketHolidayService.java \
       src/main/java/com/kista/application/service/market/MarketHolidayService.java
```

- [ ] **Step 2: MarketHolidayService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/market/MarketHolidayService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.market;`

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/market 패키지 분리"
```

---

## Task 8: admin/ 패키지 이동 + import 수정

**Files:**
- Modify: `src/main/java/com/kista/application/service/AdminService.java`
- Modify: `src/main/java/com/kista/application/service/AdminAccountService.java`
- Modify: `src/main/java/com/kista/application/service/AdminAnomaliesService.java`
- Modify: `src/main/java/com/kista/application/service/AdminAuditService.java`
- Modify: `src/main/java/com/kista/application/service/AdminTradeService.java`
- Modify: `src/test/java/com/kista/application/service/AdminServiceTest.java`

- [ ] **Step 1: 디렉토리 생성 및 파일 이동**

```bash
mkdir -p src/main/java/com/kista/application/service/admin
mkdir -p src/test/java/com/kista/application/service/admin

git mv src/main/java/com/kista/application/service/AdminService.java \
       src/main/java/com/kista/application/service/admin/AdminService.java
git mv src/main/java/com/kista/application/service/AdminAccountService.java \
       src/main/java/com/kista/application/service/admin/AdminAccountService.java
git mv src/main/java/com/kista/application/service/AdminAnomaliesService.java \
       src/main/java/com/kista/application/service/admin/AdminAnomaliesService.java
git mv src/main/java/com/kista/application/service/AdminAuditService.java \
       src/main/java/com/kista/application/service/admin/AdminAuditService.java
git mv src/main/java/com/kista/application/service/AdminTradeService.java \
       src/main/java/com/kista/application/service/admin/AdminTradeService.java

git mv src/test/java/com/kista/application/service/AdminServiceTest.java \
       src/test/java/com/kista/application/service/admin/AdminServiceTest.java
```

- [ ] **Step 2: AdminService.java — package 선언 변경 + import 추가**

파일: `src/main/java/com/kista/application/service/admin/AdminService.java`

변경 전: `package com.kista.application.service;`
변경 후:
```java
package com.kista.application.service.admin;

import com.kista.application.service.user.UserCascadeDeleter;
```

(기존 import 블록의 맨 위 또는 적절한 위치에 `import com.kista.application.service.user.UserCascadeDeleter;` 한 줄 추가)

- [ ] **Step 3: AdminAccountService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/admin/AdminAccountService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.admin;`

- [ ] **Step 4: AdminAnomaliesService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/admin/AdminAnomaliesService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.admin;`

- [ ] **Step 5: AdminAuditService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/admin/AdminAuditService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.admin;`

- [ ] **Step 6: AdminTradeService.java — package 선언 변경**

파일: `src/main/java/com/kista/application/service/admin/AdminTradeService.java`

변경 전: `package com.kista.application.service;`
변경 후: `package com.kista.application.service.admin;`

- [ ] **Step 7: AdminServiceTest.java — package 선언 변경 + import 추가**

파일: `src/test/java/com/kista/application/service/admin/AdminServiceTest.java`

변경 전: `package com.kista.application.service;`
변경 후:
```java
package com.kista.application.service.admin;

import com.kista.application.service.user.UserCascadeDeleter;
```

(기존 import 블록에 `import com.kista.application.service.user.UserCascadeDeleter;` 한 줄 추가)

- [ ] **Step 8: 컴파일 검증**

```bash
./gradlew compileJava compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "refactor: application/service/admin 패키지 분리"
```

---

## Task 9: 최종 검증 + ArchUnit 확인

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 테스트 실행**

```bash
./gradlew test --rerun-tasks
```

Expected: BUILD SUCCESSFUL, 모든 테스트 PASSED

- [ ] **Step 2: ArchUnit 규칙 검증**

```bash
./gradlew test --tests 'com.kista.architecture.*' --rerun-tasks
```

Expected: BUILD SUCCESSFUL
- `service_classes_must_be_annotated_with_service` — `com.kista.application.service..` 패턴이 서브패키지 포함하므로 통과
- `application_service_must_not_depend_on_spring_web` — 변경 없음, 통과
- `inbound_adapters_must_not_depend_on_application_layer` — 컨트롤러는 UseCase 인터페이스만 주입, 통과

- [ ] **Step 3: service 패키지 최상위 디렉토리에 파일이 없는지 확인**

```bash
ls src/main/java/com/kista/application/service/*.java 2>&1
```

Expected: `No such file or directory` (모든 파일이 서브패키지로 이동됨)

- [ ] **Step 4: 최종 커밋**

전 Step에서 이미 도메인별로 커밋됨. 필요 시 정리:

```bash
git log --oneline -10
```

Expected: 8개의 refactor 커밋이 순서대로 보임
