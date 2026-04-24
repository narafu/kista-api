# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

한국투자증권(KIS) REST API를 통한 SOXL 분할매매 자동화 서비스. Java 21 + Spring Boot 3 기반 Hexagonal Architecture.

상세 개발 규칙은 `shrimp-rules.md`에 있으며, 이 파일이 최우선 참조 문서다.

---

## 자주 쓰는 명령어

```bash
# 빌드
./gradlew bootJar                          # build/libs/app.jar 생성

# 테스트
./gradlew test                             # 전체 테스트 (병렬 실행)
./gradlew test --tests "com.kista.architecture.*"   # ArchUnit 규칙 테스트만
./gradlew test --tests "com.kista.domain.*"         # 도메인 단위 테스트만
./gradlew test --rerun-tasks               # 캐시 무시하고 강제 재실행 (UP-TO-DATE 우회)

# 실행
./gradlew bootRun --args='--spring.profiles.active=local'
docker-compose up -d                       # 앱 + PostgreSQL + Prometheus + Grafana
docker-compose up -d postgres              # DB만 기동 (로컬 개발 시)
```

---

## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).

```
domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체 (record 사용)
  strategy/      ← TradingStrategy 인터페이스 및 구현 (SoxlDivisionStrategy)
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스

application/
  service/       ← UseCase 구현체 (@Service), Port를 통해서만 외부 호출

adapter/in/
  schedule/      ← TradingScheduler (월~금 04:00 KST)
  web/           ← REST Controller + DTO (FidaOrderController)
  telegram/      ← TelegramWebhookController + TelegramBotService

adapter/out/
  kis/           ← KIS API Adapter (KisHttpClient 공통 헤더 처리)
  persistence/   ← JPA Entity + JpaRepository + PersistenceAdapter
  notify/        ← TelegramAdapter (NotifyPort 구현)
```

### 레이어 의존 방향

```
adapter.in  →  domain.port.in (UseCase)
application →  domain (model + port)
adapter.out →  domain.port.out (Port 구현)
domain      →  외부 의존 없음
```

---

## 핵심 제약 사항

### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
A = avgPrice (qty==0이면 currentPrice)  Q = soxlQty
M = A × Q   D = effectiveAmt
B = D + M   K = B ÷ 20  (scale=2, HALF_UP)
T = Q==0 ? 0 : floor(M ÷ K)
S = (20 - T×2) ÷ 100  (scale=4, HALF_UP)
P = A × 1.2  (scale=2, HALF_UP)
```

### Flyway
- `V1__`~`V3__.sql` **절대 수정 금지** — 새 마이그레이션은 `V4__...` 이후로
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화

### KIS API
- 모든 KIS 호출은 `KisHttpClient` 경유 (공통 헤더: `authorization`, `appkey`, `appsecret`, `tr_id`, `custtype: P`)
- 토큰 관리는 `KisTokenAdapter`만 담당
- Base URL: `https://openapi.koreainvestment.com:9443`

---

## 동시 수정 필요 파일 쌍

| 파일 A | 파일 B |
|--------|--------|
| 새 환경변수 추가 | `application.yml` + `.env.example` + `docker-compose.yml` |
| 새 Flyway 마이그레이션 | 해당 Entity + JpaRepository |
| Port 인터페이스 수정 | 구현 Adapter + 테스트 Mock |
| 매매 공식 변경 | `SoxlDivisionStrategyTest` |

---

## 테스트 DB

통합 테스트는 Testcontainers PostgreSQL 사용 (`testcontainers-postgresql` 의존성 포함). 실제 KIS API 통합 테스트는 모의투자 계정으로만 실행.
