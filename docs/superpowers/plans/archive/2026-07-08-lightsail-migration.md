> ⚠️ **미실행 폐기 (2026-07-11 확인)** — Fly.io 배포가 현역 유지 중이며 이 계획은 실행되지 않았다. 참고용 보관.

# Lightsail Docker 배포 고도화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fly.io에서 AWS Lightsail로 이전하기 위해 기존 Docker 기반 Lightsail 배포 설정을 개선한다.

**Architecture:** Docker + docker-compose 방식 유지. caddy `depends_on` 제거 + `--no-deps` 타겟 배포 + `lb_try_duration 120s`로 무중단 재시작 구현. liveness/readiness probe 분리로 외부 의존성 장애와 앱 헬스를 분리. Prometheus/Grafana를 Grafana Cloud + Grafana Alloy로 교체. 배포 후 헬스 게이트 + 자동 롤백으로 장애 조기 감지.

**Tech Stack:** Docker, Docker Compose, Caddy 2, Grafana Alloy, GitHub Actions, Upstash Redis (기존 `REDIS_URL` 환경변수 그대로)

## Global Constraints

- 기존 `deploy/lightsail/docker-compose.yml`의 `kista-api`, `caddy` 서비스명/컨테이너명 변경 금지
- `DEPLOY_PATH=/opt/kista-api` — 기존 서버 경로 그대로 유지
- `.env`는 서버에서 직접 관리 — GitHub Actions에서 덮어쓰지 않음
- 커밋 메시지 한글, Conventional Commit 형식 (`feat:`, `chore:`, `ci:`)
- 커밋 author 확인: `narafu <narafu@kakao.com>`
- `git push` 금지 — 커밋만 생성

---

## 파일 맵

| 파일 | 작업 |
|------|------|
| `deploy/lightsail/Caddyfile` | 수정 — liveness probe + `lb_try_duration 120s` |
| `.github/workflows/lightsail-deploy.yml` | 수정 — 배포 창 07:00~21:00 KST + 헬스 게이트 + 자동 롤백 + alloy 업로드 |
| `docker-compose.yml` (root) | 수정 — `prometheus`, `grafana` 서비스·볼륨 제거 |
| `deploy/lightsail/docker-compose.yml` | 수정 — `depends_on` 제거 + `alloy` + 로그 로테이션 + `mem_limit` |
| `deploy/lightsail/alloy-config.alloy` | 신규 생성 |
| `src/main/resources/application-prod.yml` | 수정 — liveness probe 활성화 + graceful shutdown timeout |
| `deploy/lightsail/README.md` | 수정 — 새 설정 + 롤백 runbook + 커트오버 체크리스트 |

---

## Task 1: Caddyfile — liveness probe + 롤링 retry

**Files:**
- Modify: `deploy/lightsail/Caddyfile`

**현재 내용:**
```
{$API_DOMAIN} {
    encode zstd gzip
    reverse_proxy kista-api:8080
}
```

**변경 내용:**
- `health_uri`를 `/actuator/health/liveness`로 — 외부 의존성(DB·Redis) 장애가 Caddy upstream down으로 전파되지 않도록
- `lb_try_duration 120s` — 앱 풀 스타트업(JVM+Flyway, p99 ~90s) 커버

- [ ] **Step 1: Caddyfile 전체 교체**

```
{$API_DOMAIN} {
    encode zstd gzip

    reverse_proxy kista-api:8080 {
        # 컨테이너 재시작 공백 동안 클라이언트 요청 재시도 (스타트업 p99 ~90s 커버)
        lb_try_duration 120s
        lb_try_interval 500ms

        # liveness만 체크 — DB/Redis 장애가 upstream down으로 전파되지 않도록
        health_uri      /actuator/health/liveness
        health_interval 10s
        health_timeout  5s
    }
}
```

- [ ] **Step 2: 커밋**

```bash
git add deploy/lightsail/Caddyfile
git commit -m "feat(infra): Caddy liveness probe + lb_try_duration 120s 롤링 retry 설정"
```

---

## Task 2: GitHub Actions — 배포 창 + 헬스 게이트 + 자동 롤백

**Files:**
- Modify: `.github/workflows/lightsail-deploy.yml`

**목표:**
1. 배포 창 KST **07:00~21:00** — 비DST TradingCloseScheduler(04:30 cron + 60분 대기) 보호
2. `.env`는 서버에서 직접 관리 — Actions에서 덮어쓰지 않음. 대신 배포 전 필수 키 존재 검증
3. `alloy-config.alloy` 업로드 추가
4. 배포 명령을 `--no-deps kista-api`로 타겟팅 — caddy 불필요한 재생성 방지
5. 배포 후 헬스 게이트 (3분) — 실패 시 이전 이미지로 자동 롤백

**현재 `deploy` job steps:**
1. `actions/checkout@v4`
2. `Configure SSH`
3. `Upload deployment files` (Caddyfile, docker-compose.yml SCP)
4. `Restart application` (`docker compose pull && docker compose up -d`)

- [ ] **Step 1: 배포 창 체크를 deploy job 첫 번째 step으로 추가 (checkout 이전)**

```yaml
      - name: Check deploy window (KST 07:00–21:00)
        run: |
          HOUR=$(TZ=Asia/Seoul date +%H)
          if [ "$HOUR" -ge 21 ] || [ "$HOUR" -lt 7 ]; then
            echo "::error::스케줄러 실행 시간대 배포 금지 (KST ${HOUR}시). 07:00–21:00 사이에만 배포 가능."
            exit 1
          fi
```

- [ ] **Step 2: Upload deployment files step에 alloy-config.alloy 추가**

기존:
```yaml
          scp -i ~/.ssh/lightsail -P "$LIGHTSAIL_SSH_PORT" \
            deploy/lightsail/docker-compose.yml \
            deploy/lightsail/Caddyfile \
            "$LIGHTSAIL_USER@$LIGHTSAIL_HOST:${DEPLOY_PATH}/"
```

변경:
```yaml
          scp -i ~/.ssh/lightsail -P "$LIGHTSAIL_SSH_PORT" \
            deploy/lightsail/docker-compose.yml \
            deploy/lightsail/Caddyfile \
            deploy/lightsail/alloy-config.alloy \
            "$LIGHTSAIL_USER@$LIGHTSAIL_HOST:${DEPLOY_PATH}/"
```

- [ ] **Step 3: Restart application step 전체 교체**

기존 step을 아래 두 step으로 교체:

```yaml
      - name: Verify .env and restart application
        env:
          LIGHTSAIL_HOST: ${{ secrets.LIGHTSAIL_HOST }}
          LIGHTSAIL_USER: ${{ secrets.LIGHTSAIL_USER }}
          LIGHTSAIL_SSH_PORT: ${{ secrets.LIGHTSAIL_SSH_PORT || '22' }}
          KISTA_API_IMAGE: ${{ needs.build.outputs.image }}
          GHCR_USERNAME: ${{ github.actor }}
          GHCR_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ssh -i ~/.ssh/lightsail -p "$LIGHTSAIL_SSH_PORT" "$LIGHTSAIL_USER@$LIGHTSAIL_HOST" bash << 'ENDSSH'
            set -e
            cd "${DEPLOY_PATH}"

            # 필수 환경변수 존재 검증 (.env 서버 직접 관리)
            for key in DB_URL JWT_SIGNING_KEY AES_ENCRYPTION_KEY REDIS_URL KAKAO_CLIENT_ID; do
              grep -q "^${key}=" .env || { echo "::error::필수 환경변수 누락: ${key}"; exit 1; }
            done

            # 현재 이미지 저장 (롤백용)
            PREV_IMAGE=$(docker compose ps kista-api --format '{{.Image}}' 2>/dev/null || echo "")
            echo "$PREV_IMAGE" > /tmp/kista-prev-image

            # GHCR 로그인 + kista-api만 타겟 배포 (caddy 재생성 방지)
            echo '${{ secrets.GITHUB_TOKEN }}' | docker login ghcr.io -u '${{ github.actor }}' --password-stdin
            export KISTA_API_IMAGE='${{ needs.build.outputs.image }}'
            docker compose pull kista-api
            docker compose up -d --no-deps kista-api
            docker image prune -f
          ENDSSH

      - name: Health gate & auto-rollback
        env:
          LIGHTSAIL_HOST: ${{ secrets.LIGHTSAIL_HOST }}
          LIGHTSAIL_USER: ${{ secrets.LIGHTSAIL_USER }}
          LIGHTSAIL_SSH_PORT: ${{ secrets.LIGHTSAIL_SSH_PORT || '22' }}
        run: |
          ssh -i ~/.ssh/lightsail -p "$LIGHTSAIL_SSH_PORT" "$LIGHTSAIL_USER@$LIGHTSAIL_HOST" bash << 'ENDSSH'
            set -e
            cd "${DEPLOY_PATH}"

            echo "헬스 게이트 시작 (최대 3분)..."
            for i in $(seq 1 18); do
              if curl -sf localhost:8080/actuator/health/liveness > /dev/null 2>&1; then
                echo "✓ 헬스체크 통과 (${i}회 시도)"
                exit 0
              fi
              echo "  대기 중... (${i}/18)"
              sleep 10
            done

            # 헬스체크 실패 → 이전 이미지로 자동 롤백
            PREV_IMAGE=$(cat /tmp/kista-prev-image)
            if [ -n "$PREV_IMAGE" ]; then
              echo "✗ 헬스체크 실패 — 이전 이미지로 롤백: $PREV_IMAGE"
              export KISTA_API_IMAGE="$PREV_IMAGE"
              docker compose up -d --no-deps kista-api
            else
              echo "✗ 헬스체크 실패 — 이전 이미지 정보 없음, 수동 복구 필요"
            fi
            exit 1
          ENDSSH
```

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/lightsail-deploy.yml
git commit -m "ci: 배포 창 07:00~21:00 + --no-deps 타겟 배포 + 헬스 게이트 + 자동 롤백"
```

---

## Task 3: root docker-compose.yml — Prometheus/Grafana 제거

**Files:**
- Modify: `docker-compose.yml`

로컬 개발용 docker-compose에서 prometheus/grafana를 제거한다. (메트릭은 Grafana Cloud로 이동)

- [ ] **Step 1: prometheus, grafana 서비스 블록 제거**

`docker-compose.yml`에서 아래 서비스 전체 제거:
- `prometheus:` 서비스 블록
- `grafana:` 서비스 블록

- [ ] **Step 2: 볼륨 선언 제거**

`volumes:` 섹션에서 제거:
- `prometheus_data:`
- `grafana_data:`

- [ ] **Step 3: infra/prometheus 디렉토리 삭제**

```bash
git rm -r infra/prometheus/
```

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.yml
git commit -m "chore(infra): 로컬 Prometheus/Grafana 제거 — Grafana Cloud로 이전"
```

---

## Task 4: deploy/lightsail/docker-compose.yml + Grafana Alloy

**Files:**
- Modify: `deploy/lightsail/docker-compose.yml`
- Create: `deploy/lightsail/alloy-config.alloy`

**변경 내용:**
1. caddy `depends_on` 제거 — lb_try_duration이 있으면 기동 순서 보장 불필요. 재시작 중에도 retry로 대기
2. `kista-api`에 `stop_grace_period: 35s` + 로그 로테이션 + `mem_limit`
3. Grafana Alloy 서비스 추가
4. caddy에도 로그 로테이션 + `mem_limit`

- [ ] **Step 1: deploy/lightsail/docker-compose.yml 전체 교체**

```yaml
services:
  kista-api:
    image: ${KISTA_API_IMAGE:?KISTA_API_IMAGE is required}
    container_name: kista-api
    env_file:
      - .env
    environment:
      SPRING_PROFILES_ACTIVE: prod
      JAVA_OPTS: ${JAVA_OPTS:--Xmx768m -Xms128m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom}
    expose:
      - "8080"
    stop_grace_period: 35s   # Spring graceful shutdown 30s + 여유 5s
    mem_limit: 1200m         # 힙768m + 메타256m + 코드캐시64m + 네이티브 여유
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      start_period: 180s
      retries: 3
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"

  caddy:
    image: caddy:2.9-alpine   # 버전 핀 — 무빙 태그 방지
    container_name: kista-api-caddy
    env_file:
      - .env
    # depends_on 제거 — lb_try_duration이 기동 순서 보장, 리부트 시 caddy 선기동 가능
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    mem_limit: 64m
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "3"

  alloy:
    image: grafana/alloy:latest
    container_name: kista-api-alloy
    volumes:
      - ./alloy-config.alloy:/etc/alloy/config.alloy:ro
    environment:
      GRAFANA_CLOUD_PROMETHEUS_URL: ${GRAFANA_CLOUD_PROMETHEUS_URL}
      GRAFANA_CLOUD_USERNAME: ${GRAFANA_CLOUD_USERNAME}
      GRAFANA_CLOUD_API_KEY: ${GRAFANA_CLOUD_API_KEY}
    command: run --server.http.listen-addr=0.0.0.0:12345 /etc/alloy/config.alloy
    mem_limit: 300m
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "3"

volumes:
  caddy_data:
  caddy_config:
```

- [ ] **Step 2: deploy/lightsail/alloy-config.alloy 생성**

```hcl
// kista-api 메트릭 수집 → Grafana Cloud remote_write
prometheus.scrape "kista_api" {
  targets = [{
    __address__      = "kista-api:8080",
    __metrics_path__ = "/actuator/prometheus",
  }]
  forward_to      = [prometheus.remote_write.grafana_cloud.receiver]
  scrape_interval = "30s"
  scrape_timeout  = "10s"
}

prometheus.remote_write "grafana_cloud" {
  endpoint {
    url = sys.env("GRAFANA_CLOUD_PROMETHEUS_URL")

    basic_auth {
      username = sys.env("GRAFANA_CLOUD_USERNAME")
      password = sys.env("GRAFANA_CLOUD_API_KEY")
    }
  }
}
```

- [ ] **Step 3: 커밋**

```bash
git add deploy/lightsail/docker-compose.yml deploy/lightsail/alloy-config.alloy
git commit -m "feat(infra): depends_on 제거 + Alloy + stop_grace_period + 로그 로테이션 + mem_limit"
```

---

## Task 5: application-prod.yml — liveness probe + graceful shutdown timeout

**Files:**
- Modify: `src/main/resources/application-prod.yml`

**현재 내용:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
      connection-timeout: 20000
      idle-timeout: 300000
      max-lifetime: 1800000
  jpa:
    show-sql: false

management:
  endpoint:
    health:
      show-details: never

logging:
  level:
    root: WARN
    com.kista: INFO
```

- [ ] **Step 1: application-prod.yml 전체 교체**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
      connection-timeout: 20000
      idle-timeout: 300000
      max-lifetime: 1800000
  jpa:
    show-sql: false
  lifecycle:
    timeout-per-shutdown-phase: 30s  # docker stop_grace_period 35s와 쌍으로 관리

management:
  health:
    probes:
      enabled: true  # /actuator/health/liveness + /actuator/health/readiness 활성화
  endpoint:
    health:
      show-details: never

logging:
  level:
    root: WARN
    com.kista: INFO
```

- [ ] **Step 2: 커밋**

```bash
git add src/main/resources/application-prod.yml
git commit -m "chore(config): prod liveness probe 활성화 + graceful shutdown timeout 30s 명시"
```

---

## Task 6: README.md 업데이트

**Files:**
- Modify: `deploy/lightsail/README.md`

- [ ] **Step 1: README.md 전체 교체**

```markdown
# Lightsail deployment

`kista-api`를 AWS Lightsail 단일 인스턴스에서 Docker Compose + Caddy로 운영한다.

## 서버 레이아웃

```text
/opt/kista-api/
├── .env                    ← 서버에서 직접 관리 (Actions에서 덮어쓰지 않음)
├── Caddyfile               ← GitHub Actions 업로드
├── docker-compose.yml      ← GitHub Actions 업로드
└── alloy-config.alloy      ← GitHub Actions 업로드
```

## 초기 서버 설정 (최초 1회)

1. Lightsail 2GB Ubuntu 22.04 LTS 인스턴스 생성 (Tokyo 리전)
2. 정적 IP 할당 → 도메인 A 레코드 연결
3. 인바운드 포트 `80`, `443` 개방 / `8080` 비공개 유지
4. Docker 설치:
   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER
   ```
5. 배포 경로 생성 및 `.env` 작성:
   ```bash
   sudo mkdir -p /opt/kista-api
   sudo chown $USER:$USER /opt/kista-api
   vi /opt/kista-api/.env   # 아래 .env 내용 참고
   ```
6. 로그 로테이션 설정 (`/etc/docker/daemon.json`):
   ```json
   {
     "live-restore": true,
     "log-driver": "json-file",
     "log-opts": { "max-size": "50m", "max-file": "5" }
   }
   ```
7. 자동 재부팅 비활성화 (스케줄러 보호):
   ```bash
   sudo sed -i 's/^Unattended-Upgrade::Automatic-Reboot "true"/Unattended-Upgrade::Automatic-Reboot "false"/' \
     /etc/apt/apt.conf.d/50unattended-upgrades
   ```
8. 2GB swap 추가:
   ```bash
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
   sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   ```

## GitHub Secrets

| Secret | 설명 |
|--------|------|
| `LIGHTSAIL_HOST` | 서버 IP 또는 도메인 |
| `LIGHTSAIL_USER` | SSH 사용자명 |
| `LIGHTSAIL_SSH_KEY` | SSH 개인키 (PEM) |
| `LIGHTSAIL_SSH_PORT` | SSH 포트 (기본값 22, 생략 가능) |

`.env`는 서버에서 직접 관리 — Actions에 시크릿으로 올리지 않음.

## .env 내용

```dotenv
API_DOMAIN=api.example.com

DB_URL=
DB_USERNAME=
DB_PASSWORD=
REDIS_URL=                  # Upstash: rediss://default:xxx@xxx.upstash.io:6379

JWT_SIGNING_KEY=
AES_ENCRYPTION_KEY=
ADMIN_KAKAO_IDS=

KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
CORS_ALLOWED_ORIGINS=

TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
INTERNAL_API_TOKEN=

TOSS_ADMIN_CLIENT_ID=
TOSS_ADMIN_CLIENT_SECRET=
ALPACA_API_KEY=
ALPACA_API_SECRET=
FIREBASE_SERVICE_ACCOUNT_JSON=   # 반드시 단일 행 JSON

GRAFANA_CLOUD_PROMETHEUS_URL=
GRAFANA_CLOUD_USERNAME=
GRAFANA_CLOUD_API_KEY=
```

## 배포 흐름

1. `main` push → `verify` job (컴파일 + ArchUnit)
2. Docker 이미지 빌드 → GHCR push
3. 배포 창 체크 (KST 07:00–21:00만 허용)
4. 필수 환경변수 존재 검증 (서버 `.env` 기준)
5. `docker compose pull kista-api && docker compose up -d --no-deps kista-api`
6. 헬스 게이트: `/actuator/health/liveness` 최대 3분 폴링
7. 실패 시 이전 이미지로 자동 롤백
8. Caddy `lb_try_duration 120s`가 컨테이너 재시작 공백을 클라이언트에 투명하게 처리

## 배포 시간 제한

스케줄러 보호를 위해 KST **21:00–07:00 배포 자동 차단**.
- `TradingOpenScheduler`: 월~금 22:00 KST
- `TradingCloseScheduler`: 화~토 04:30 KST + 최대 60분 대기 (비DST 시 ~05:30까지)

## 롤백 Runbook

**자동 롤백**: 헬스 게이트 실패 시 Actions가 이전 이미지로 자동 복구.

**수동 롤백**: GHCR에 SHA 태그 이미지가 보존됨.
```bash
cd /opt/kista-api
# 롤백할 이미지 태그 확인
docker images | grep kista-api

# 이전 이미지로 교체
export KISTA_API_IMAGE=ghcr.io/<org>/kista-api:<previous-sha>
docker compose up -d --no-deps kista-api
```

**Flyway 관련 롤백 주의**: 신규 마이그레이션이 포함된 배포는 `validate-on-migrate: true` 때문에 이전 이미지로 롤백 시 기동 실패할 수 있음. 이 경우 DB 마이그레이션 수동 롤백 후 이미지 롤백 필요. Breaking migration 배포는 별도 주의 필요.

## Flyway 배포 주의사항

- **Additive migration** (컬럼 추가, 테이블 추가): 정상 무중단 배포
- **Breaking migration**: 구 컨테이너가 이미 종료된 후 실행되므로 충돌 없으나, 실패 시 이전 이미지 롤백 불가. 별도 다운타임 계획 필요.

## 모니터링

- **메트릭**: Grafana Alloy → Grafana Cloud (`/actuator/prometheus` 30s scrape)
- **헬스체크**: UptimeRobot → `https://{API_DOMAIN}/actuator/health` 5분 간격 (full health — DB·Redis 포함)
- **스케줄러 감시**: Healthchecks.io dead-man's-switch — `SchedulerJobRunner` 완료 시 ping (TODO)
- **로그**: `docker compose logs -f kista-api` (서버 SSH)

## 커트오버 체크리스트

- [ ] Lightsail 인스턴스 생성 + 정적 IP + 도메인 A 레코드
- [ ] `.env` 작성 및 필수 키 검증
- [ ] `docker compose up -d` 수동 실행 + 헬스체크 확인
- [ ] `/actuator/health` 외부 접근 확인
- [ ] 카카오 OAuth redirect URI → 새 도메인으로 변경
- [ ] `CORS_ALLOWED_ORIGINS` → 새 API 도메인 포함 확인
- [ ] Telegram webhook 재등록: `https://{NEW_DOMAIN}/telegram/webhook`
- [ ] FIDA 호출측 URL → 새 도메인으로 변경 (`/api/internal/**`)
- [ ] kista-ui `NEXT_PUBLIC_API_URL` → 새 도메인으로 변경
- [ ] UptimeRobot 헬스체크 URL 업데이트
- [ ] Fly.io 1~2일 유지 후 종료

## Fly.io 롤백

`fly-deploy.yml` workflow_dispatch로 수동 실행 가능 — 커트오버 후 1~2일간 유지.
```
