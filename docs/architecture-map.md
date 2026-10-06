# 아키텍처 맵

전체 구조를 줌 레벨별로 보여주는 그림 모음이다. 텍스트 규칙·모듈 목록의 SSOT는 `docs/agents/architecture.md`이고, 이 문서는 그림과 링크만 둔다 — 서술이 필요하면 그쪽을 고친다.

| 레벨 | 질문 | 위치 |
|---|---|---|
| L0 시스템 컨텍스트 | 어떤 프로세스와 외부 시스템이 있나 | 아래 L0 |
| L1 빌드·스키마 경계 | 어느 모듈이 어느 jar·DB 스키마 소속인가 | 아래 L1 |
| L1.5 프로세스 간 통신 | root ↔ trading-core는 무엇으로 대화하나 | 아래 L1.5 |
| L2 모듈 의존 그래프 | 모듈끼리 누가 누구를 참조하나 | **자동 생성** — 아래 L2 |
| L3 핵심 흐름 | 매매 배치는 어떤 순서로 도나 | 아래 L3, 상세는 `docs/agents/workflow.md` |

## L0 시스템 컨텍스트

```mermaid
flowchart LR
    UI["kista-ui<br/>Next.js"]
    FIDA["FIDA<br/>PRIVACY 기준표 발행"]

    subgraph OCI["OCI kista-api-server · docker-compose"]
        CADDY["Caddy<br/>HTTPS 종료 · 경로 라우팅"]
        API["kista-api<br/>app.jar · blue/green"]
        SCH["kista-scheduler<br/>app.jar · 비매매 스케쥴러"]
        TRD["kista-trading<br/>trading-core.jar · 매매 배치 + HTTP"]
        PG[("PostgreSQL<br/>kistadb")]
        RD[("Redis")]
    end

    subgraph EXT["외부 API"]
        KIS["KIS"]
        TOSS["Toss증권"]
        ALPACA["Alpaca<br/>캔들·휴장일"]
        KAKAO["Kakao OAuth"]
        KB["KB부동산"]
        FG["공포탐욕지수"]
        TG["Telegram"]
        FCM["FCM"]
    end

    UI --> CADDY
    FIDA -->|"/api/internal/fida-orders"| CADDY
    CADDY -->|"/api/admin/scheduler/**"| SCH
    CADDY -->|"@trading 경로<br/>accounts · orders · trading-cycles · stats/summary …"| TRD
    CADDY -->|"그 외 전부"| API

    API -->|"/api/internal/** · X-Internal-Token"| TRD
    API & SCH & TRD <--> RD
    API & SCH --> PG
    TRD --> PG

    TRD --> KIS & TOSS & ALPACA
    API --> KAKAO & FCM
    SCH --> KB & FG & ALPACA
    API & SCH & TRD --> TG
```

- 라우팅 SSOT: `deploy/server/caddy/kista-api.caddy` (`CaddyRoutingTest`가 컨트롤러 경로와 대조)
- 배포·role 상세: `docs/agents/docker-infra.md` "서버 배포 방식"

## L1 빌드·스키마 경계

```mermaid
flowchart TB
    subgraph shared[":shared · outbound 0"]
        sk["sharedkernel<br/>공유 enum·이벤트"]
        ct["contract<br/>내부 API·Redis wire 타입"]
        pf["platform<br/>persistence·crypto·redis·time"]
    end

    subgraph api[":api → app.jar"]
        user; admin; finance; notify; market; benchmark; web
    end

    subgraph tc[":trading-core → trading-core.jar"]
        trading; matching; broker; account; privacy
        marketcalendar; tradingstats; tradingnotify; tradingweb
    end

    api --> shared
    tc --> shared
    api -. "컴파일 의존 0<br/>HTTP·Redis로만 통신" .- tc

    subgraph db["kistadb 스키마 (다른 서비스 스키마 참조·FK 금지)"]
        s1[("public · finance · kista_ref<br/>Flyway db/migration")]
        s2[("trading · trading_ref<br/>Flyway db/migration-trading")]
    end
    api --> s1
    tc --> s2
```

- 경계 검증: `GradleModuleBoundaryTest`(Gradle 방향), `InternalApiContractTest`(wire 타입), `ModulithArchitectureTest`(모듈), `HexagonalArchitectureTest`(레이어)

## L1.5 프로세스 간 통신

root → trading-core는 내부 HTTP와 Redis Stream, trading-core → root는 Redis만 쓴다(trading-core는 root HTTP를 호출하지 않는다).

```mermaid
flowchart LR
    subgraph root["root (kista-api · kista-scheduler)"]
        rOut["*/adapter/out/internal<br/>*HttpAdapter"]
        rUser["user<br/>UserEventStreamPublisher"]
        rAdmin["admin<br/>AppErrorStreamConsumer"]
        rNotify["notify<br/>PushNotificationStreamConsumer<br/>RedisTradeEventSubscriber → SSE"]
    end

    subgraph tc["trading-core (kista-trading)"]
        tIn["*InternalController<br/>/api/internal/**"]
        tUser["trading<br/>UserDeleted·UserNotifyProfile<br/>StreamConsumer"]
        tErr["trading<br/>AppErrorStreamPublisher"]
        tNotify["tradingnotify<br/>RedisPushNotificationPublisher<br/>RedisTradeEventPublisher"]
    end

    rOut -->|"HTTP · contract.*"| tIn
    rUser -->|"stream:user.deleted<br/>stream:user.notify-profile.changed"| tUser
    tErr -->|"stream:app.error"| rAdmin
    tNotify -->|"stream:user.push-notification.requested"| rNotify
    tNotify -->|"Pub/Sub trade.event"| rNotify
```

- Stream 공통 규약: `platform.redis.RedisStreams`, 키·그룹명: `RedisStreamConfig`
- JWT 블랙리스트도 Redis 공유(root `RedisBlacklistAdapter` 기록 → trading-core `RedisTokenBlacklistReader` 조회)

## L2 모듈 의존 그래프 (자동 생성)

손으로 그리지 않는다. `ModulithArchitectureTest`가 `Documenter`로 실제 의존 기준 PlantUML을 생성한다.

```bash
./gradlew :test --tests 'com.kista.architecture.ModulithArchitectureTest'
# 출력: build/spring-modulith-docs/components.puml (전체), module-<name>.puml (모듈별)
```

IntelliJ PlantUML Integration 플러그인으로 `.puml`을 열면 렌더링된다.

## L3 마감 매매 배치 (화~토 04:30 KST)

개장 배치(`TradingOpenScheduler`, 월~금 22:30 KST)도 같은 골격이다. 단계·예외 처리 상세는 `docs/agents/workflow.md`.

```mermaid
sequenceDiagram
    autonumber
    participant S as TradingCloseScheduler
    participant Svc as TradingService
    participant Plan as TradingCandidatePlanner
    participant Alloc as TradingOrderBudgetAllocator
    participant Exec as TradingOrderExecutor
    participant B as Broker (KIS/Toss)
    participant DB as trading.orders
    participant R as TradingReporter
    participant N as tradingnotify

    S->>S: StrategyPort.findAllActive() → 사이클별 context 빌드
    S->>Svc: executeBatch(contexts)
    Svc->>B: ticker별 현재가 일괄 조회
    Svc->>Plan: 전략 계산 · BUY cap 사전 계산
    loop 계좌별 (TradingParallelRunner, AccountBudgetLock)
        Plan->>Alloc: allocate(candidates, tradeDate)
        Alloc->>B: live 잔고 · 판매가능수량
        Alloc->>DB: 승인 주문 PLANNED 저장
    end
    Svc->>Svc: DstInfo.waitUntilOrderTime() (비DST 60분 대기)
    Svc->>B: 접수 대상 현재가 재조회
    Svc->>Exec: BuyOrderPriceCapper 재캡 후 접수
    Exec->>B: AT_CLOSE 주문 접수
    Exec->>DB: PLACED / FAILED
    Svc->>R: 체결 리포트
    R-->>N: TradingReportReadyEvent (AFTER_COMMIT, EPR)
    N->>N: 사용자 봇 Telegram · Redis 푸시
```
