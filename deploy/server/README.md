# Lightsail deployment

`kista-api`를 AWS Lightsail 단일 인스턴스에서 Docker Compose + Caddy로 운영한다.

## 서버 레이아웃

```text
/opt/kista-api/
├── .env                    ← 서버에서 직접 관리 (Actions에서 덮어쓰지 않음)
├── Caddyfile               ← GitHub Actions 업로드
└── docker-compose.yml      ← GitHub Actions 업로드
```

## 초기 서버 설정 (최초 1회)

1. Lightsail 2GB Ubuntu 22.04 LTS 인스턴스 생성 (Tokyo 리전) — 반드시 amd64(x86_64) 아키텍처로 생성한다(GitHub Actions 러너가 amd64로 이미지를 빌드하므로 arm64 인스턴스에서는 컨테이너가 기동하지 않는다)
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

Port `8080`은 외부에 공개하지 않는다 — Caddy가 Docker 네트워크를 통해 접근한다.

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

GRAFANA_CLOUD_OTLP_ENABLED=true
GRAFANA_CLOUD_OTLP_ENDPOINT=       # 예: https://otlp-gateway-prod-ap-northeast-0.grafana.net/otlp
GRAFANA_CLOUD_OTLP_AUTH_HEADER=    # Grafana Cloud 발급 "Basic xxxx" 인증 헤더 값 전체

HEARTBEAT_OPEN_URL=          # healthchecks.io 개장 스케쥴러 dead-man's switch (미설정 시 핑 생략)
HEARTBEAT_CLOSE_URL=         # healthchecks.io 마감 스케쥴러 dead-man's switch (미설정 시 핑 생략)
```

Optional JVM override for the 2 GB instance:

```dotenv
JAVA_OPTS=-Xmx768m -Xms128m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom
```

## 배포 흐름

1. `main` push → `verify` job (전체 테스트 스위트, ArchUnit 포함)
2. Docker 이미지 빌드 → GHCR push
3. 배포 창 체크 (KST 개장 22:20~23:40, 마감 04:20~06:20 시간대 자동 차단 — `workflow_dispatch` force=true로 우회 가능)
4. 필수 환경변수 존재 검증 (서버 `.env` 기준)
5. `docker compose pull kista-api && docker compose up -d --no-deps kista-api`
6. 헬스 게이트: 호스트에서 `docker inspect --format '{{.State.Health.Status}}' kista-api`로 컨테이너 헬스 상태를 10초 간격 최대 5분(300초) 폴링 — 컨테이너 healthcheck의 start_period(180s)+retries×interval(90s)보다 여유 있게 설정
7. 실패 시 이전 이미지로 자동 롤백
8. Caddy `lb_try_duration 120s`가 컨테이너 재시작 공백을 클라이언트에 투명하게 처리

## 배포 시간 제한

개장 스케쥴러 실행 구간(월~금 22:20~23:40 KST)과 마감 스케쥴러 실행 구간(화~토 04:20~06:20 KST)에 배포 자동 차단 — `workflow_dispatch` `force=true`로 긴급 우회 가능.
- `TradingOpenScheduler`: 월~금 22:30 KST
- `TradingCloseScheduler`: 화~토 04:30 KST + 최대 60분 대기 (비DST 시 ~05:30까지)

## 롤백 Runbook

**자동 롤백**: 헬스 게이트 실패 시 Actions가 이전 이미지로 자동 복구. 자동 롤백 후 롤백된 컨테이너의 헬스는 재검증되지 않으므로, Actions 실패 알림을 받으면 서버에서 `docker inspect --format '{{.State.Health.Status}}' kista-api`로 수동 확인 필요.

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

- **메트릭**: 앱이 `micrometer-registry-otlp`로 Grafana Cloud에 60초 간격 직접 OTLP push (`management.otlp.metrics.export`, `GRAFANA_CLOUD_OTLP_*` 환경변수) — 별도 사이드카 불필요
- **헬스체크**: UptimeRobot → `https://{API_DOMAIN}/actuator/health` 5분 간격 (full health — DB·Redis 포함)
- **스케줄러 감시**: Healthchecks.io dead-man's-switch — `TradingOpenScheduler`/`TradingCloseScheduler` 실행 완료 시 `HeartbeatPort.pingOpen()`/`pingClose()` GET 핑. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전). healthchecks.io 콘솔에서 각 체크의 예상 주기(개장 ~22:30 KST, 마감 ~04:30 KST + DST 여유)를 등록해야 실제로 미실행이 감지됨
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
- [ ] healthchecks.io 체크 2개 생성(개장/마감) + `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 등록 + Grafana Cloud 알림 규칙 연동
- [ ] Fly.io 1~2일 유지 후 종료

## Fly.io 롤백

`fly-deploy.yml` workflow_dispatch로 수동 실행 가능 — 커트오버 후 1~2일간 유지.
