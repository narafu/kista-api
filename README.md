# kista-api

[![CI](https://github.com/narafu/kista-api/actions/workflows/ci.yml/badge.svg)](https://github.com/narafu/kista-api/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)

KISTA(Key Investment Strategy & Trading Automation) — 정밀한 투자 전략을 기반으로 작동하는 다중 증권사 통합 자동매매 SaaS의 백엔드.
프론트엔드는 별도 저장소 [`kista-ui`](https://github.com/narafu/kista-ui)(Next.js 16, Vercel)와 연동한다.

## 기술 스택

Java 21 · Spring Boot 3 · Hexagonal Architecture · PostgreSQL · Flyway · OCI

## 아키텍처

### 계층 구조 (Hexagonal Architecture)

레이어 의존 방향(`adapter → application → domain`)은 ArchUnit(`HexagonalArchitectureTest`)이 빌드 시 강제 검증한다.

```mermaid
graph TB
    subgraph in["adapter/in"]
        web["web/ REST Controller\n+DTO"]
        schedule["schedule/ 스케쥴러"]
        telegram_in["telegram/ Webhook"]
    end

    subgraph core["application + domain (ArchUnit 강제)"]
        usecase["port/in — UseCase 인터페이스"]
        service["application/service\nUseCase 구현체 (package-private)"]
        domain["domain/model\n순수 record, Spring 무의존"]
        portout["port/out — *Port 인터페이스"]
    end

    subgraph out["adapter/out"]
        persistence["persistence/ JPA"]
        broker["kis/ · toss/ · mock/ BrokerAdapter"]
        notify["notify/ Telegram · FCM"]
        sse["sse/ SseEmitterRegistry"]
        kakao_out["kakao/ OAuth"]
        redis_out["redis/ Blacklist"]
    end

    web --> usecase
    schedule --> usecase
    telegram_in --> usecase
    usecase --> service
    service --> domain
    service --> portout
    portout -.구현.-> persistence
    portout -.구현.-> broker
    portout -.구현.-> notify
    portout -.구현.-> sse
    portout -.구현.-> kakao_out
    portout -.구현.-> redis_out
```

### 트레이딩 스케쥴러 흐름

```mermaid
sequenceDiagram
    participant S1 as TradingOpenScheduler<br/>(월~금 22:30 KST)
    participant S2 as TradingCloseScheduler<br/>(화~토 04:30 KST, 장마감 30분 전)
    participant TF as TradingExecutionFacade
    participant KIS as KIS API
    participant DB as PostgreSQL
    participant Noti as Telegram / FCM

    S1->>TF: executeBatch() — 전략 전체 순회
    TF->>KIS: 잔고/보유수량 조회 (BrokerAdapterRegistry 경유)
    TF->>TF: CycleOrderStrategy.compute()<br/>(INFINITE/PRIVACY/VR 별 주문 계산)
    TF->>DB: Order 저장 (계획 상태)
    TF->>KIS: 매도 선접수 (INFINITE)

    S2->>TF: executeBatch() — 장마감 임박
    TF->>KIS: BuyOrderPriceCapper 보정 후 매수 접수
    TF->>DB: CyclePositionPersistor — 포지션 스냅샷 저장
    alt holdings = 0 (전량 청산)
        TF->>DB: 사이클 종료 + cycleSeedType 기반 재등록<br/>(VR은 유지 — endsCycleOnLiquidation=false)
    end
    TF->>Noti: 리포트/오류 알림
    Noti->>Noti: SseEmitterRegistry로 실시간 거래 알림 push
```

## 배포

```mermaid
graph TB
    subgraph GH["GitHub"]
        RepoUI["kista-ui repo"]
        RepoAPI["kista-api repo"]
        BackupAction["DB 백업 워크플로"]
    end

    subgraph VercelInfra["Vercel"]
        UIApp["kista-ui (Next.js)"]
    end

    subgraph OciInfra["OCI"]
        APIApp["kista-api"]
    end

    subgraph SupaInfra["Supabase"]
        PG2[("PostgreSQL")]
    end

    subgraph Monitoring["외부 모니터링"]
        Uptime["가동 모니터링"]
        HC["스케쥴러 생존 확인"]
        Grafana["메트릭 추세 관찰"]
    end

    RepoUI -->|"GitHub 통합 자동 배포"| UIApp
    RepoAPI -->|"main push → 전체 테스트(ArchUnit 포함)<br/>→ 이미지 빌드·GHCR push<br/>→ SSH 배포"| APIApp
    UIApp -->|"HTTPS"| APIApp
    APIApp --> PG2
    BackupAction -->|"pg_dump → 암호화 → artifact"| PG2
    Uptime --> APIApp
    APIApp --> HC
    APIApp --> Grafana
```

- Supabase Free 플랜은 자체 백업이 없어 정기적으로 `pg_dump` → 암호화 → GitHub Actions artifact로 별도 백업한다.
- 외부 모니터링은 서로 다른 실패 모드를 감지한다: 가동 모니터링(서버 다운) / 생존 확인(스케쥴러 정지) / 메트릭 추세(리소스 악화).
