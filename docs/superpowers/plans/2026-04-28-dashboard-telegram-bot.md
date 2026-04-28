# Dashboard + Telegram Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** REST API(거래내역/포트폴리오/지정가주문) + 정적 HTML 대시보드, Telegram FSM 봇(조회·수동실행) 구현

**Architecture:** Hexagonal Architecture 준수. Application Service 계층(GetTradeHistoryUseCase, GetPortfolioUseCase, ExecuteFidaOrderUseCase 구현)을 통해 Port를 호출하며, adapter/in/web 과 adapter/in/telegram 이 UseCase 인터페이스만 의존. ArchUnit 규칙 자동 검증.

**Tech Stack:** Java 21 Virtual Thread, Spring Boot 3, Spring MVC (@WebMvcTest), Mockito, Chart.js CDN, RestTemplate

---

## 파일 맵

| 파일 | 역할 |
|------|------|
| `application/service/TradeHistoryService.java` | GetTradeHistoryUseCase 구현 |
| `application/service/PortfolioService.java` | GetPortfolioUseCase 구현 |
| `application/service/FidaOrderService.java` | ExecuteFidaOrderUseCase 구현 |
| `adapter/in/web/DashboardController.java` | REST API 4개 엔드포인트 |
| `adapter/in/web/dto/TradeHistoryResponse.java` | 응답 DTO |
| `adapter/in/web/dto/PortfolioSnapshotResponse.java` | 응답 DTO |
| `adapter/in/web/dto/FidaOrderRequestDto.java` | 요청 DTO (Jakarta Validation) |
| `resources/static/index.html` | Chart.js 대시보드 (DOM 안전 메서드 사용) |
| `adapter/in/telegram/TelegramUpdate.java` | 웹훅 페이로드 record |
| `adapter/in/telegram/BotState.java` | FSM 상태 enum |
| `adapter/in/telegram/TelegramApiClient.java` | RestTemplate sendMessage 래퍼 |
| `adapter/in/telegram/TelegramBotService.java` | FSM 명령 처리기 |
| `adapter/in/telegram/TelegramWebhookController.java` | POST /telegram/webhook |

---

## Task 1: TradeHistoryService

**Files:**
- Create: `src/main/java/com/kista/application/service/TradeHistoryService.java`
- Create: `src/test/java/com/kista/application/service/TradeHistoryServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/kista/application/service/TradeHistoryServiceTest.java
package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.out.TradeHistoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeHistoryServiceTest {

    @Mock
    TradeHistoryPort tradeHistoryPort;

    @InjectMocks
    TradeHistoryService sut;

    @Test
    void getHistory_delegates_to_port() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 28);
        TradeHistory h = new TradeHistory(UUID.randomUUID(), from, "SOXL", "SOXL_DIVISION",
                Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("25.00"), new BigDecimal("250.00"),
                Order.OrderStatus.PLACED, "KIS001", "MAIN", Instant.now());
        when(tradeHistoryPort.findBy(from, to, "SOXL")).thenReturn(List.of(h));

        List<TradeHistory> result = sut.getHistory(from, to, "SOXL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("SOXL");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.application.service.TradeHistoryServiceTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol` (TradeHistoryService 미존재)

- [ ] **Step 3: 최소 구현 작성**

```java
// src/main/java/com/kista/application/service/TradeHistoryService.java
package com.kista.application.service;

import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import com.kista.domain.port.out.TradeHistoryPort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class TradeHistoryService implements GetTradeHistoryUseCase {

    private final TradeHistoryPort tradeHistoryPort;

    public TradeHistoryService(TradeHistoryPort tradeHistoryPort) {
        this.tradeHistoryPort = tradeHistoryPort;
    }

    @Override
    public List<TradeHistory> getHistory(LocalDate from, LocalDate to, String symbol) {
        return tradeHistoryPort.findBy(from, to, symbol);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.application.service.TradeHistoryServiceTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/TradeHistoryService.java \
        src/test/java/com/kista/application/service/TradeHistoryServiceTest.java
git commit -m "feat(service): add TradeHistoryService implementing GetTradeHistoryUseCase"
```

---

## Task 2: PortfolioService

**Files:**
- Create: `src/main/java/com/kista/application/service/PortfolioService.java`
- Create: `src/test/java/com/kista/application/service/PortfolioServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/kista/application/service/PortfolioServiceTest.java
package com.kista.application.service;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.port.out.PortfolioSnapshotPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    PortfolioSnapshotPort portfolioSnapshotPort;

    @InjectMocks
    PortfolioService sut;

    @Test
    void getCurrent_returns_latest_snapshot() {
        PortfolioSnapshot snap = snapshot(LocalDate.now());
        when(portfolioSnapshotPort.findRecent(7)).thenReturn(List.of(snap));

        assertThat(sut.getCurrent()).isEqualTo(snap);
    }

    @Test
    void getCurrent_throws_when_no_snapshot_in_last_7_days() {
        when(portfolioSnapshotPort.findRecent(7)).thenReturn(List.of());

        assertThatThrownBy(() -> sut.getCurrent())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("포트폴리오");
    }

    @Test
    void getSnapshots_delegates_days_to_port() {
        PortfolioSnapshot snap = snapshot(LocalDate.now());
        when(portfolioSnapshotPort.findRecent(30)).thenReturn(List.of(snap));

        assertThat(sut.getSnapshots(30)).hasSize(1);
    }

    private PortfolioSnapshot snapshot(LocalDate date) {
        return new PortfolioSnapshot(UUID.randomUUID(), date, "SOXL", 100,
                new BigDecimal("25.0000"), new BigDecimal("26.0000"),
                new BigDecimal("2600.00"), new BigDecimal("1000.00"),
                new BigDecimal("3600.00"), Instant.now());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.application.service.PortfolioServiceTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol`

- [ ] **Step 3: 최소 구현 작성**

```java
// src/main/java/com/kista/application/service/PortfolioService.java
package com.kista.application.service;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.out.PortfolioSnapshotPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PortfolioService implements GetPortfolioUseCase {

    private final PortfolioSnapshotPort portfolioSnapshotPort;

    public PortfolioService(PortfolioSnapshotPort portfolioSnapshotPort) {
        this.portfolioSnapshotPort = portfolioSnapshotPort;
    }

    @Override
    public PortfolioSnapshot getCurrent() {
        return portfolioSnapshotPort.findRecent(7).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("포트폴리오 스냅샷이 없습니다."));
    }

    @Override
    public List<PortfolioSnapshot> getSnapshots(int days) {
        return portfolioSnapshotPort.findRecent(days);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.application.service.PortfolioServiceTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/PortfolioService.java \
        src/test/java/com/kista/application/service/PortfolioServiceTest.java
git commit -m "feat(service): add PortfolioService implementing GetPortfolioUseCase"
```

---

## Task 3: FidaOrderService

**Files:**
- Create: `src/main/java/com/kista/application/service/FidaOrderService.java`
- Create: `src/test/java/com/kista/application/service/FidaOrderServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/kista/application/service/FidaOrderServiceTest.java
package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.KisTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FidaOrderServiceTest {

    @Mock KisTokenPort kisTokenPort;
    @Mock KisOrderPort kisOrderPort;

    @InjectMocks
    FidaOrderService sut;

    @Test
    void execute_places_limit_order_with_token() {
        when(kisTokenPort.getToken()).thenReturn("test-token");
        when(kisOrderPort.place(eq("test-token"), any())).thenAnswer(inv -> inv.getArgument(1));
        FidaOrderRequest req = new FidaOrderRequest(
                "SOXL", Order.OrderDirection.BUY, 5, new BigDecimal("25.50"));

        sut.execute(req);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(kisOrderPort).place(eq("test-token"), captor.capture());
        Order placed = captor.getValue();
        assertThat(placed.orderType()).isEqualTo(Order.OrderType.LIMIT);
        assertThat(placed.symbol()).isEqualTo("SOXL");
        assertThat(placed.qty()).isEqualTo(5);
        assertThat(placed.price()).isEqualByComparingTo("25.50");
        assertThat(placed.phase()).isEqualTo("FIDA");
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.application.service.FidaOrderServiceTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol`

- [ ] **Step 3: 최소 구현 작성**

```java
// src/main/java/com/kista/application/service/FidaOrderService.java
package com.kista.application.service;

import com.kista.domain.model.Order;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.KisTokenPort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class FidaOrderService implements ExecuteFidaOrderUseCase {

    private final KisTokenPort kisTokenPort;
    private final KisOrderPort kisOrderPort;

    public FidaOrderService(KisTokenPort kisTokenPort, KisOrderPort kisOrderPort) {
        this.kisTokenPort = kisTokenPort;
        this.kisOrderPort = kisOrderPort;
    }

    @Override
    public void execute(FidaOrderRequest request) {
        String token = kisTokenPort.getToken();
        Order order = new Order(
                LocalDate.now(),
                request.symbol(),
                Order.OrderType.LIMIT,
                request.direction(),
                request.qty(),
                request.price(),
                Order.OrderStatus.PLACED,
                null,
                "FIDA");
        kisOrderPort.place(token, order);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.application.service.FidaOrderServiceTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: ArchUnit 포함 전체 테스트 확인**

```bash
./gradlew test --tests "com.kista.architecture.*" --tests "com.kista.application.*"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/application/service/FidaOrderService.java \
        src/test/java/com/kista/application/service/FidaOrderServiceTest.java
git commit -m "feat(service): add FidaOrderService implementing ExecuteFidaOrderUseCase"
```

---

## Task 4: DashboardController + DTOs + WebMvcTest

**Files:**
- Create: `src/main/java/com/kista/adapter/in/web/dto/TradeHistoryResponse.java`
- Create: `src/main/java/com/kista/adapter/in/web/dto/PortfolioSnapshotResponse.java`
- Create: `src/main/java/com/kista/adapter/in/web/dto/FidaOrderRequestDto.java`
- Create: `src/main/java/com/kista/adapter/in/web/DashboardController.java`
- Create: `src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java`

- [ ] **Step 1: DTO 3개 작성**

```java
// src/main/java/com/kista/adapter/in/web/dto/TradeHistoryResponse.java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Order;
import com.kista.domain.model.TradeHistory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TradeHistoryResponse(
        UUID id,
        LocalDate tradeDate,
        String symbol,
        String strategy,
        Order.OrderType orderType,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price,
        BigDecimal amountUsd,
        Order.OrderStatus status,
        String kisOrderId,
        String phase,
        Instant createdAt
) {
    public static TradeHistoryResponse from(TradeHistory h) {
        return new TradeHistoryResponse(
                h.id(), h.tradeDate(), h.symbol(), h.strategy(),
                h.orderType(), h.direction(), h.qty(), h.price(),
                h.amountUsd(), h.status(), h.kisOrderId(), h.phase(), h.createdAt());
    }
}
```

```java
// src/main/java/com/kista/adapter/in/web/dto/PortfolioSnapshotResponse.java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.PortfolioSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PortfolioSnapshotResponse(
        UUID id,
        LocalDate snapshotDate,
        String symbol,
        int qty,
        BigDecimal avgPrice,
        BigDecimal currentPrice,
        BigDecimal marketValueUsd,
        BigDecimal usdDeposit,
        BigDecimal totalAssetUsd,
        Instant createdAt
) {
    public static PortfolioSnapshotResponse from(PortfolioSnapshot s) {
        return new PortfolioSnapshotResponse(
                s.id(), s.snapshotDate(), s.symbol(), s.qty(),
                s.avgPrice(), s.currentPrice(), s.marketValueUsd(),
                s.usdDeposit(), s.totalAssetUsd(), s.createdAt());
    }
}
```

```java
// src/main/java/com/kista/adapter/in/web/dto/FidaOrderRequestDto.java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Order;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record FidaOrderRequestDto(
        @NotBlank String symbol,
        @NotNull Order.OrderDirection direction,
        @Positive int qty,
        @NotNull @Positive BigDecimal price
) {}
```

- [ ] **Step 2: 실패하는 WebMvcTest 작성**

```java
// src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java
package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.dto.FidaOrderRequestDto;
import com.kista.domain.model.Order;
import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean GetTradeHistoryUseCase getTradeHistoryUseCase;
    @MockBean GetPortfolioUseCase getPortfolioUseCase;
    @MockBean ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    @Test
    void getTrades_returns_200_with_list() throws Exception {
        TradeHistory h = new TradeHistory(UUID.randomUUID(), LocalDate.now(), "SOXL", "SOXL_DIVISION",
                Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("25.00"), new BigDecimal("250.00"),
                Order.OrderStatus.PLACED, "KIS001", "MAIN", Instant.now());
        when(getTradeHistoryUseCase.getHistory(any(), any(), any())).thenReturn(List.of(h));

        mockMvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("SOXL"))
                .andExpect(jsonPath("$[0].qty").value(10));
    }

    @Test
    void getPortfolioCurrent_returns_200() throws Exception {
        PortfolioSnapshot snap = new PortfolioSnapshot(UUID.randomUUID(), LocalDate.now(), "SOXL",
                100, new BigDecimal("25.0000"), new BigDecimal("26.0000"),
                new BigDecimal("2600.00"), new BigDecimal("1000.00"),
                new BigDecimal("3600.00"), Instant.now());
        when(getPortfolioUseCase.getCurrent()).thenReturn(snap);

        mockMvc.perform(get("/api/portfolio/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("SOXL"))
                .andExpect(jsonPath("$.qty").value(100));
    }

    @Test
    void getPortfolioSnapshots_returns_200() throws Exception {
        when(getPortfolioUseCase.getSnapshots(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/portfolio/snapshots?days=7"))
                .andExpect(status().isOk());
    }

    @Test
    void placeFidaOrder_returns_201() throws Exception {
        FidaOrderRequestDto dto = new FidaOrderRequestDto(
                "SOXL", Order.OrderDirection.BUY, 5, new BigDecimal("25.50"));

        mockMvc.perform(post("/api/orders/fida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());
    }

    @Test
    void placeFidaOrder_invalid_body_returns_400() throws Exception {
        mockMvc.perform(post("/api/orders/fida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.web.DashboardControllerTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol` (DashboardController 미존재)

- [ ] **Step 4: DashboardController 구현**

```java
// src/main/java/com/kista/adapter/in/web/DashboardController.java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FidaOrderRequestDto;
import com.kista.adapter.in.web.dto.PortfolioSnapshotResponse;
import com.kista.adapter.in.web.dto.TradeHistoryResponse;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final GetTradeHistoryUseCase getTradeHistoryUseCase;
    private final GetPortfolioUseCase getPortfolioUseCase;
    private final ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    public DashboardController(GetTradeHistoryUseCase getTradeHistoryUseCase,
                               GetPortfolioUseCase getPortfolioUseCase,
                               ExecuteFidaOrderUseCase executeFidaOrderUseCase) {
        this.getTradeHistoryUseCase = getTradeHistoryUseCase;
        this.getPortfolioUseCase = getPortfolioUseCase;
        this.executeFidaOrderUseCase = executeFidaOrderUseCase;
    }

    @GetMapping("/trades")
    public List<TradeHistoryResponse> getTrades(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "SOXL") String symbol) {
        LocalDate resolvedFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        return getTradeHistoryUseCase.getHistory(resolvedFrom, resolvedTo, symbol)
                .stream().map(TradeHistoryResponse::from).toList();
    }

    @GetMapping("/portfolio/current")
    public PortfolioSnapshotResponse getPortfolioCurrent() {
        return PortfolioSnapshotResponse.from(getPortfolioUseCase.getCurrent());
    }

    @GetMapping("/portfolio/snapshots")
    public List<PortfolioSnapshotResponse> getPortfolioSnapshots(
            @RequestParam(defaultValue = "30") int days) {
        return getPortfolioUseCase.getSnapshots(days)
                .stream().map(PortfolioSnapshotResponse::from).toList();
    }

    @PostMapping("/orders/fida")
    @ResponseStatus(HttpStatus.CREATED)
    public void placeFidaOrder(@RequestBody @Valid FidaOrderRequestDto dto) {
        executeFidaOrderUseCase.execute(new FidaOrderRequest(
                dto.symbol(), dto.direction(), dto.qty(), dto.price()));
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.web.DashboardControllerTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/dto/ \
        src/main/java/com/kista/adapter/in/web/DashboardController.java \
        src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java
git commit -m "feat(web): add DashboardController with trade/portfolio/fida-order endpoints"
```

---

## Task 5: index.html 프론트엔드

**Files:**
- Create: `src/main/resources/static/index.html`

데이터는 우리 자신의 REST API에서 오므로 신뢰할 수 있지만, DOM 안전 메서드(`textContent`, `createElement`)를 사용하여 XSS 위험 원천 차단.

- [ ] **Step 1: index.html 작성**

`src/main/resources/static/index.html` 에 다음 내용을 작성한다:

```
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>KISTA Dashboard</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: 'Segoe UI', sans-serif; background: #f5f5f5; color: #333; padding: 24px; }
    h1 { margin-bottom: 24px; font-size: 1.4rem; }
    h2 { font-size: 1rem; margin-bottom: 12px; color: #555; }
    .card { background: white; border-radius: 8px; padding: 20px; margin-bottom: 24px;
            box-shadow: 0 1px 4px rgba(0,0,0,.08); }
    table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #eee; }
    th { background: #fafafa; font-weight: 600; }
  </style>
</head>
<body>
<h1>KISTA Dashboard</h1>
<div class="card">
  <h2>총자산 추이 (최근 30일)</h2>
  <canvas id="portfolioChart" height="80"></canvas>
</div>
<div class="card">
  <h2>거래 내역 (최근 30일)</h2>
  <table id="tradeTable">
    <thead>
      <tr>
        <th>날짜</th><th>방향</th><th>유형</th><th>수량</th>
        <th>가격</th><th>금액</th><th>상태</th>
      </tr>
    </thead>
    <tbody id="tradeBody"></tbody>
  </table>
</div>
<script>
function cell(text) {
  const td = document.createElement('td');
  td.textContent = text;
  return td;
}

(async () => {
  try {
    const r = await fetch('/api/portfolio/snapshots?days=30');
    const data = await r.json();
    new Chart(document.getElementById('portfolioChart'), {
      type: 'line',
      data: {
        labels: data.map(s => s.snapshotDate),
        datasets: [{
          label: '총자산 (USD)',
          data: data.map(s => parseFloat(s.totalAssetUsd)),
          tension: 0.3, fill: true,
          borderColor: '#1565c0', backgroundColor: 'rgba(21,101,192,.1)'
        }]
      },
      options: {
        plugins: { legend: { display: false } },
        scales: { y: { ticks: { callback: v => '$' + v.toLocaleString() } } }
      }
    });
  } catch (e) { console.error('포트폴리오 로드 실패', e); }

  try {
    const r = await fetch('/api/trades');
    const data = await r.json();
    const tbody = document.getElementById('tradeBody');
    if (!data.length) {
      const tr = document.createElement('tr');
      const td = document.createElement('td');
      td.colSpan = 7;
      td.textContent = '거래 내역 없음';
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }
    data.forEach(h => {
      const tr = document.createElement('tr');
      tr.appendChild(cell(h.tradeDate));
      tr.appendChild(cell(h.direction));
      tr.appendChild(cell(h.orderType));
      tr.appendChild(cell(String(h.qty)));
      tr.appendChild(cell('$' + parseFloat(h.price).toFixed(4)));
      tr.appendChild(cell('$' + parseFloat(h.amountUsd).toFixed(2)));
      tr.appendChild(cell(h.status));
      tbody.appendChild(tr);
    });
  } catch (e) {
    const td = document.createElement('td');
    td.colSpan = 7;
    td.textContent = '데이터 로드 실패';
    const tr = document.createElement('tr');
    tr.appendChild(td);
    document.getElementById('tradeBody').appendChild(tr);
  }
})();
</script>
</body>
</html>
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/resources/static/index.html
git commit -m "feat(web): add Chart.js dashboard with portfolio chart and trade history table"
```

---

## Task 6: TelegramApiClient

**Files:**
- Create: `src/main/java/com/kista/adapter/in/telegram/TelegramApiClient.java`
- Create: `src/test/java/com/kista/adapter/in/telegram/TelegramApiClientTest.java`

`TelegramApiClient`는 기존 `TelegramConfig`의 `telegramRestTemplate` 빈과 `TelegramProperties`를 재사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/kista/adapter/in/telegram/TelegramApiClientTest.java
package com.kista.adapter.in.telegram;

import com.kista.adapter.out.notify.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramApiClientTest {

    @Mock RestTemplate restTemplate;

    @Test
    void sendMessage_posts_to_telegram_api() {
        TelegramProperties props = new TelegramProperties("test-token", "12345");
        TelegramApiClient sut = new TelegramApiClient(restTemplate, props);

        sut.sendMessage("12345", "안녕");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(contains("/sendMessage"), captor.capture(), eq(String.class));
        assertThat(captor.getValue())
                .containsEntry("chat_id", "12345")
                .containsEntry("text", "안녕");
    }

    @Test
    void sendMessage_skips_when_token_blank() {
        TelegramProperties props = new TelegramProperties("", "12345");
        TelegramApiClient sut = new TelegramApiClient(restTemplate, props);

        sut.sendMessage("12345", "안녕");

        verifyNoInteractions(restTemplate);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.telegram.TelegramApiClientTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol`

- [ ] **Step 3: TelegramApiClient 구현**

```java
// src/main/java/com/kista/adapter/in/telegram/TelegramApiClient.java
package com.kista.adapter.in.telegram;

import com.kista.adapter.out.notify.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);
    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate restTemplate;
    private final TelegramProperties props;

    TelegramApiClient(RestTemplate telegramRestTemplate, TelegramProperties props) {
        this.restTemplate = telegramRestTemplate;
        this.props = props;
    }

    void sendMessage(String chatId, String text) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("Telegram botToken 미설정 — 메시지 전송 생략");
            return;
        }
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/sendMessage";
            restTemplate.postForObject(url, Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML"), String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.telegram.TelegramApiClientTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/telegram/TelegramApiClient.java \
        src/test/java/com/kista/adapter/in/telegram/TelegramApiClientTest.java
git commit -m "feat(telegram): add TelegramApiClient for bot reply messages"
```

---

## Task 7: TelegramBotService (FSM)

**Files:**
- Create: `src/main/java/com/kista/adapter/in/telegram/BotState.java`
- Create: `src/main/java/com/kista/adapter/in/telegram/TelegramUpdate.java`
- Create: `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java`
- Create: `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`

- [ ] **Step 1: BotState enum 및 TelegramUpdate record 작성**

```java
// src/main/java/com/kista/adapter/in/telegram/BotState.java
package com.kista.adapter.in.telegram;

enum BotState {
    IDLE,
    AWAITING_RUN_CONFIRM
}
```

```java
// src/main/java/com/kista/adapter/in/telegram/TelegramUpdate.java
package com.kista.adapter.in.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramUpdate(
        @JsonProperty("update_id") Long updateId,
        @JsonProperty("message") Message message
) {
    public record Message(
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("chat") Chat chat,
            @JsonProperty("text") String text
    ) {}

    public record Chat(
            @JsonProperty("id") Long id
    ) {}
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java
package com.kista.adapter.in.telegram;

import com.kista.domain.model.Order;
import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock TelegramApiClient apiClient;
    @Mock GetTradeHistoryUseCase getTradeHistoryUseCase;
    @Mock GetPortfolioUseCase getPortfolioUseCase;
    @Mock ExecuteTradingUseCase executeTradingUseCase;

    TelegramBotService sut;
    static final long CHAT_ID = 12345L;

    @BeforeEach
    void setUp() {
        sut = new TelegramBotService(String.valueOf(CHAT_ID), apiClient,
                getTradeHistoryUseCase, getPortfolioUseCase, executeTradingUseCase);
    }

    private TelegramUpdate update(String text) {
        return new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), text));
    }

    @Test
    void help_command_returns_command_list() {
        sut.handle(update("/help"));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(eq(String.valueOf(CHAT_ID)), captor.capture());
        assertThat(captor.getValue()).contains("/status").contains("/history").contains("/run");
    }

    @Test
    void status_command_returns_portfolio_info() {
        PortfolioSnapshot snap = new PortfolioSnapshot(UUID.randomUUID(), LocalDate.now(), "SOXL",
                100, new BigDecimal("25.0000"), new BigDecimal("26.0000"),
                new BigDecimal("2600.00"), new BigDecimal("1000.00"),
                new BigDecimal("3600.00"), Instant.now());
        when(getPortfolioUseCase.getCurrent()).thenReturn(snap);

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("100주");
    }

    @Test
    void status_when_no_snapshot_returns_fallback_message() {
        when(getPortfolioUseCase.getCurrent()).thenThrow(new NoSuchElementException());

        sut.handle(update("/status"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("데이터가 없습니다");
    }

    @Test
    void history_command_with_days_delegates_to_usecase() {
        when(getTradeHistoryUseCase.getHistory(any(), any(), eq("SOXL"))).thenReturn(List.of());

        sut.handle(update("/history 14"));

        verify(getTradeHistoryUseCase).getHistory(
                LocalDate.now().minusDays(14), LocalDate.now(), "SOXL");
    }

    @Test
    void run_command_transitions_to_awaiting_confirm() {
        sut.handle(update("/run"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("yes/no");
    }

    @Test
    void run_confirm_yes_starts_trading_execution() throws InterruptedException {
        sut.handle(update("/run"));
        reset(apiClient);

        sut.handle(update("yes"));

        Thread.sleep(200);
        verify(executeTradingUseCase, atLeastOnce()).execute();
    }

    @Test
    void run_confirm_no_cancels_and_returns_to_idle() {
        sut.handle(update("/run"));
        reset(apiClient);

        sut.handle(update("no"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("취소");
    }

    @Test
    void unauthorized_chatId_is_ignored() {
        TelegramUpdate badUpdate = new TelegramUpdate(1L,
                new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(99999L), "/help"));

        sut.handle(badUpdate);

        verifyNoInteractions(apiClient);
    }

    @Test
    void unknown_command_returns_help_hint() {
        sut.handle(update("/unknown"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("/help");
    }
}
```

- [ ] **Step 3: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.telegram.TelegramBotServiceTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol`

- [ ] **Step 4: TelegramBotService 구현**

```java
// src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java
package com.kista.adapter.in.telegram;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

@Component
class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    private final String adminChatId;
    private final TelegramApiClient apiClient;
    private final GetTradeHistoryUseCase getTradeHistoryUseCase;
    private final GetPortfolioUseCase getPortfolioUseCase;
    private final ExecuteTradingUseCase executeTradingUseCase;
    private final ConcurrentHashMap<Long, BotState> stateMap = new ConcurrentHashMap<>();

    TelegramBotService(
            @Value("${telegram.chat-id:}") String adminChatId,
            TelegramApiClient apiClient,
            GetTradeHistoryUseCase getTradeHistoryUseCase,
            GetPortfolioUseCase getPortfolioUseCase,
            ExecuteTradingUseCase executeTradingUseCase) {
        this.adminChatId = adminChatId;
        this.apiClient = apiClient;
        this.getTradeHistoryUseCase = getTradeHistoryUseCase;
        this.getPortfolioUseCase = getPortfolioUseCase;
        this.executeTradingUseCase = executeTradingUseCase;
    }

    void handle(TelegramUpdate update) {
        if (update.message() == null || update.message().text() == null) return;
        long chatId = update.message().chat().id();
        String text = update.message().text().trim();

        if (!String.valueOf(chatId).equals(adminChatId)) {
            log.warn("Unauthorized webhook from chatId={}", chatId);
            return;
        }

        BotState state = stateMap.getOrDefault(chatId, BotState.IDLE);
        String reply = switch (state) {
            case IDLE -> handleIdle(chatId, text);
            case AWAITING_RUN_CONFIRM -> handleRunConfirm(chatId, text);
        };
        if (reply != null) {
            apiClient.sendMessage(String.valueOf(chatId), reply);
        }
    }

    private String handleIdle(long chatId, String text) {
        String cmd = text.split("\\s+")[0].toLowerCase();
        return switch (cmd) {
            case "/start", "/help" -> """
                    사용 가능한 명령어:
                    /status — 최신 포트폴리오 현황
                    /history [days] — 거래 내역 (기본 7일)
                    /run — 수동 매매 실행
                    /cancel — 진행 중인 대화 취소""";
            case "/status" -> buildStatusMessage();
            case "/history" -> buildHistoryMessage(parseHistoryDays(text));
            case "/run" -> {
                stateMap.put(chatId, BotState.AWAITING_RUN_CONFIRM);
                yield "정말 수동 매매를 실행할까요? (yes/no)";
            }
            default -> "알 수 없는 명령어입니다. /help 를 입력하세요.";
        };
    }

    private String handleRunConfirm(long chatId, String text) {
        return switch (text.toLowerCase()) {
            case "yes" -> {
                stateMap.put(chatId, BotState.IDLE);
                Thread.ofVirtual().start(() -> {
                    try {
                        executeTradingUseCase.execute();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("수동 매매 스레드 인터럽트");
                    } catch (Exception e) {
                        log.error("수동 매매 실패", e);
                    }
                });
                yield "매매 실행을 시작합니다. 결과는 완료 후 알림으로 전송됩니다.";
            }
            case "no", "/cancel" -> {
                stateMap.put(chatId, BotState.IDLE);
                yield "취소되었습니다.";
            }
            default -> "yes 또는 no 로 답해주세요.";
        };
    }

    private String buildStatusMessage() {
        try {
            PortfolioSnapshot s = getPortfolioUseCase.getCurrent();
            return String.format(
                    "<b>포트폴리오 현황 [%s]</b>%n보유: %d주 @ $%.4f%n현재가: $%.4f%n평가액: $%.2f%n예수금: $%.2f%n총자산: $%.2f",
                    s.snapshotDate(), s.qty(), s.avgPrice(), s.currentPrice(),
                    s.marketValueUsd(), s.usdDeposit(), s.totalAssetUsd());
        } catch (NoSuchElementException e) {
            return "포트폴리오 데이터가 없습니다.";
        }
    }

    private String buildHistoryMessage(int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days);
        List<TradeHistory> list = getTradeHistoryUseCase.getHistory(from, to, "SOXL");
        if (list.isEmpty()) return "최근 " + days + "일 거래 내역이 없습니다.";
        StringBuilder sb = new StringBuilder("<b>최근 " + days + "일 거래 내역</b>\n");
        list.forEach(h -> sb.append(String.format("%s %s %s %d주 $%.4f%n",
                h.tradeDate(), h.direction(), h.orderType(), h.qty(), h.price())));
        return sb.toString().trim();
    }

    private int parseHistoryDays(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 7;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.telegram.TelegramBotServiceTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/telegram/BotState.java \
        src/main/java/com/kista/adapter/in/telegram/TelegramUpdate.java \
        src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java \
        src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java
git commit -m "feat(telegram): add TelegramBotService with FSM (IDLE/AWAITING_RUN_CONFIRM)"
```

---

## Task 8: TelegramWebhookController + WebMvcTest

**Files:**
- Create: `src/main/java/com/kista/adapter/in/telegram/TelegramWebhookController.java`
- Create: `src/test/java/com/kista/adapter/in/telegram/TelegramWebhookControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/kista/adapter/in/telegram/TelegramWebhookControllerTest.java
package com.kista.adapter.in.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelegramWebhookController.class)
class TelegramWebhookControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean TelegramBotService botService;

    @Test
    void webhook_accepts_valid_payload_and_returns_200() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1,\"message\":{\"message_id\":1,\"chat\":{\"id\":12345},\"text\":\"/help\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void webhook_with_no_message_field_returns_200() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":2}"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 오류 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.telegram.TelegramWebhookControllerTest" 2>&1 | tail -20
```

Expected: `error: cannot find symbol`

- [ ] **Step 3: TelegramWebhookController 구현**

```java
// src/main/java/com/kista/adapter/in/telegram/TelegramWebhookController.java
package com.kista.adapter.in.telegram;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class TelegramWebhookController {

    private final TelegramBotService botService;

    public TelegramWebhookController(TelegramBotService botService) {
        this.botService = botService;
    }

    @PostMapping("/telegram/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void handleWebhook(@RequestBody TelegramUpdate update) {
        botService.handle(update);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.telegram.TelegramWebhookControllerTest"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 전체 테스트 + ArchUnit 통과 확인**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL` — 모든 테스트 통과

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/telegram/TelegramWebhookController.java \
        src/test/java/com/kista/adapter/in/telegram/TelegramWebhookControllerTest.java
git commit -m "feat(telegram): add TelegramWebhookController for POST /telegram/webhook"
```
