# Server deployment (OCI)

> ⚠️ **배포 전 필독 — 이 브랜치를 아직 `main`에 병합하지 말 것**
> 이 문서와 `server-deploy.yml`은 `kista-infra` 레포(Caddy·Postgres·Redis 소유)가 실제로 서버에 배포된 **이후** 상태를 전제로 작성됐다.
> `kista-infra`가 아직 배포되지 않은 시점에 이 브랜치를 `main`에 병합하면 `push: main` 트리거로 `server-deploy.yml`이 즉시 자동 실행되어 운영이 깨진다:
> - `--remove-orphans` 없이 재기동되므로 현재 살아있는 구 caddy/redis 컨테이너가 orphan 처리되어 Caddy 라우팅이 끊긴다
> - `REDIS_URL=redis://redis:6379`가 아직 존재하지 않는 `data_net`을 가리켜 Redis 연결 실패 → Toss 토큰 스토어가 DB fallback 없이 fail-closed(503) → 실거래 경로 장애 (`docs/agents/docker-infra.md` "Fly.io 다중 인스턴스 Toss 토큰 조정" 참고)
> **병합 가능 조건**: kista-infra의 Phase 0(인스턴스 재편) + kista-infra 자체 최초 배포(`kista-infra/.github/workflows/server-deploy.yml` 성공 실행)가 완료된 뒤에만 이 브랜치를 `main`에 병합할 것.

`kista-api`를 단일 인스턴스(현재 OCI)에서 Docker Compose로 운영한다. 리버스 프록시(Caddy)·Postgres·Redis는
`kista-infra` 레포가 소유하며, 이 레포는 `shared_net`(Caddy 라우팅)·`data_net`(Postgres/Redis 접근) 두 외부
네트워크에 합류만 한다.

## 서버 레이아웃

```text
/opt/kista-api/
├── .env                    ← kista-infra 배포 워크플로가 렌더링·덮어쓴다 (이 레포의 Actions는 관여하지 않음, 아래 "GitHub Secrets" 참고)
└── docker-compose.yml      ← GitHub Actions 업로드
```

## 초기 서버 설정 (최초 1회)

1. OCI 인스턴스(이미 생성됨): `VM.Standard.A1.Flex`(Ampere arm64), 2 OCPU, 12GB RAM, 부트 볼륨 50GB, Ubuntu 24.04 LTS — **arm64이므로 배포 워크플로가 `linux/arm64`로 이미지를 빌드한다**(다른 아키텍처로 재생성 시 `server-deploy.yml`의 `platforms` 값도 함께 변경 필요). 최초 12GB/부트 200GB로 생성했다가, free-tier 스토리지(리전당 200GB) 전량을 부트 볼륨 하나가 점유해 다른 인스턴스를 만들 여유가 없어져 부트 볼륨만 50GB로 재생성함(2026-08-03) — OCI는 볼륨 in-place 축소를 지원하지 않아 재생성이 유일한 방법. OCPU·메모리는 볼륨과 달리 Flexible shape라 `oci compute instance update --shape-config`로 실행 중에도 변경 가능(적용에 재부팅 필요) — 계획한 인스턴스 3대 합계가 OCI Always Free Ampere A1 총 한도(4 OCPU + 24GB RAM)를 정확히 채우도록 재생성 직후 8GB→12GB로 조정함
2. 정적 공인 IP: 인스턴스 생성 시 Reserved Public IP 할당(또는 별도 예약 공인 IP 연결) → 도메인 A 레코드 연결 — **DNS 제공자가 프록시 기능을 지원하면(예: Cloudflare) 반드시 "DNS only"(프록시 끔, 회색 구름)로 설정**. 프록시를 켜면 DNS 제공자가 TLS를 가로채 Caddy의 Let's Encrypt 자동 인증서 발급(HTTP-01 challenge)이 실패한다
   - **예약(Reserved) 공인 IP로 무중단 호스트 교체**: 반드시 Reserved(에페메럴 아님)로 할당해두면, 이후 인스턴스를 재생성해야 할 때(스펙 변경·볼륨 축소 등) 새 인스턴스를 임시 공인 IP로 완전히 기동·스모크 테스트한 뒤 예약 IP만 `oci network public-ip update --private-ip-id <새 인스턴스 private-ip-ocid>`로 재할당하면 된다 — 도메인·DNS·GitHub Secret(`SERVER_HOST`) 변경 없이 호스트를 교체할 수 있다(2026-08-03 부트 볼륨 축소 재생성 시 실사용)
3. 인바운드 포트 개방 — 2단계:
   - OCI 콘솔: 인스턴스가 속한 VCN의 Security List(또는 연결된 NSG)에 Ingress Rule 추가 — TCP `80`, `443`, source `0.0.0.0/0` (`22`는 이미 열려있을 것)
   - **주의**: OCI Ubuntu 이미지는 콘솔 레벨 방화벽 외에 OS 레벨에서도 `iptables`(netfilter-persistent)로 SSH 외 인바운드를 기본 차단해두는 경우가 있다. 콘솔에서 포트를 열었는데 접속이 안 되면 인스턴스에서 `sudo iptables -L INPUT -n --line-numbers`로 OS 방화벽 규칙을 먼저 확인할 것 — 막혀 있으면 80/443 허용 규칙 추가 후 `sudo netfilter-persistent save`로 저장한다 (OCI 이미지 버전에 따라 달라질 수 있어 실제 접속 테스트로 최종 확인 필요)
   - `8080`은 비공개 유지 — kista-infra의 Caddy가 `shared_net`을 통해 접근한다
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

## GitHub Secrets

| Secret | 설명 |
|--------|------|
| `SERVER_HOST` | 서버 IP 또는 도메인 |
| `SERVER_USER` | SSH 사용자명 |
| `SERVER_SSH_KEY` | SSH 개인키 (PEM) |
| `SERVER_SSH_PORT` | SSH 포트 (기본값 22, 생략 가능) |

`.env`는 이 레포의 Actions가 아니라 `kista-infra` 레포의 배포 워크플로가 관리한다 — `kista-infra`에 GPG로 암호화 커밋된 `secrets/kista-api.env.gpg`를 복호화해 매 배포마다 `/opt/kista-api/.env`를 렌더링·덮어쓴다. 값을 바꾸려면 `kista-infra`의 `scripts/env.sh edit kista-api`로 수정 후 커밋·배포해야 하며, 서버에서 `.env`를 직접 수정해도 다음 kista-infra 배포 시 되돌아간다.

**주의**: kista-infra의 `.env` 렌더링은 이 레포의 배포를 트리거하지 않는다 — `/opt/kista-api/.env` 파일 내용은 바뀌지만, 이미 떠 있는 `kista-api` 컨테이너는 재시작 전까지 구 값을 메모리에 유지한다. 시크릿 로테이션을 실제로 반영하려면 kista-infra 배포 뒤 이 레포의 `server-deploy.yml`도 별도로 실행(재배포)해야 한다.

## .env 내용

Redis는 kista-infra 레포가 소유하는 컨테이너로 `data_net`에서 `redis` alias로 접근하며, `REDIS_URL`이
`docker-compose.yml`에 `redis://redis:6379`로 하드코딩되어 있다 — `.env`에 별도 설정 불필요.

```dotenv
API_DOMAIN=api.example.com

DB_URL=
DB_USERNAME=
DB_PASSWORD=

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

Optional JVM override (기본값은 `docker-compose.yml`에 이미 OCI 12GB 인스턴스 기준으로 반영돼 있다 — 다른 값이 필요할 때만 `.env`에 명시):

```dotenv
JAVA_OPTS=-Xmx3072m -Xms256m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=96m -XX:+UseG1GC -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom
```

## 배포 흐름

1. `main` push → `verify` job (전체 테스트 스위트, ArchUnit 포함)
2. Docker 이미지 빌드 → GHCR push
3. 배포 창 체크 (KST 개장 22:20~23:40, 마감 04:20~06:20 시간대 자동 차단 — `workflow_dispatch` force=true로 우회 가능)
4. 필수 환경변수 존재 검증 (서버 `.env` 기준)
5. `docker compose pull kista-api && docker compose up -d --no-deps kista-api`
6. 헬스 게이트: 호스트에서 `docker inspect --format '{{.State.Health.Status}}' kista-api`로 컨테이너 헬스 상태를 10초 간격 최대 5분(300초) 폴링 — 컨테이너 healthcheck의 start_period(180s)+retries×interval(90s)보다 여유 있게 설정
7. 실패 시 이전 이미지로 자동 롤백
8. kista-infra의 Caddy `lb_try_duration 120s`(kista-infra 레포 소유 설정)가 컨테이너 재시작 공백을 클라이언트에 투명하게 처리

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

**이미지 디스크 정리 참고**: 배포 워크플로의 `docker image prune -f`는 dangling(태그 없는) 레이어만 제거한다 — 롤백에 쓰이는 SHA 태그 이미지는 계속 쌓인다. 디스크 압박이 느껴지면 수동으로 `docker image prune -af --filter "until=720h"`(30일 이상 지난 이미지만) 등으로 정리하되, 최근 롤백 후보 몇 개는 남겨둘 것.

## Flyway 배포 주의사항

- **Additive migration** (컬럼 추가, 테이블 추가): 정상 무중단 배포
- **Breaking migration**: 구 컨테이너가 이미 종료된 후 실행되므로 충돌 없으나, 실패 시 이전 이미지 롤백 불가. 별도 다운타임 계획 필요.

## 모니터링

- **메트릭**: 앱이 `micrometer-registry-otlp`로 Grafana Cloud에 60초 간격 직접 OTLP push (`management.otlp.metrics.export`, `GRAFANA_CLOUD_OTLP_*` 환경변수) — 별도 사이드카 불필요
- **Redis 영속성**: kista-infra 레포 소유 — 상세 설정(AOF 등)은 kista-infra README 참고. `kista-api`에 `depends_on: redis`는 의도적으로 미사용(Spring Data Redis lazy connection이라 앱 부팅을 막지 않음)
- **헬스체크**: UptimeRobot → `https://{API_DOMAIN}/actuator/health` 5분 간격 (full health — DB·Redis 포함)
- **스케줄러 감시**: Healthchecks.io dead-man's-switch — `TradingOpenScheduler`/`TradingCloseScheduler` 실행 완료 시 `HeartbeatPort.pingOpen()`/`pingClose()` GET 핑. `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 미설정 시 핑 생략(배포 안전). healthchecks.io 콘솔에서 각 체크의 예상 주기(개장 ~22:30 KST, 마감 ~04:30 KST + DST 여유)를 등록해야 실제로 미실행이 감지됨
- **로그**: `docker compose logs -f kista-api` (서버 SSH)

## fida

fida는 이 인스턴스(A: kista-api/kista-ui/kista-infra)와 **별도의 OCI 인스턴스(B)**에서 독립 운영된다 — 같은 서버에
병행 배포되지 않으며, `kista-infra`의 Caddy·네트워크·시크릿 어느 것도 fida를 대상으로 하지 않는다. 따라서 fida 배포
절차는 이 레포·kista-infra 레포 양쪽 모두 범위 밖이다. fida→kista-api `/api/internal/**` 호출은 공인 도메인을
경유하므로(무변경) 이 전환과 무관하게 그대로 동작한다.

## 커트오버 체크리스트

- [ ] OCI 인스턴스 방화벽 확인(Security List/NSG + OS iptables) + 정적 IP + 도메인 A 레코드
- [ ] `.env` 작성 및 필수 키 검증
- [ ] `docker compose up -d` 수동 실행 + 헬스체크 확인
- [ ] `/actuator/health` 외부 접근 확인
- [ ] 카카오 OAuth redirect URI → 새 도메인으로 변경
- [ ] `CORS_ALLOWED_ORIGINS` → 새 API 도메인 포함 확인
- [ ] Telegram webhook 재등록: `https://{NEW_DOMAIN}/telegram/webhook`
- [ ] FIDA 호출측 URL → 새 도메인으로 변경 (`/api/internal/**`)
- [ ] kista-ui `NEXT_PUBLIC_API_URL` → 새 도메인으로 변경
- [ ] UptimeRobot 헬스체크 URL 업데이트
- [x] healthchecks.io 체크 2개 생성(개장/마감) + `HEARTBEAT_OPEN_URL`/`HEARTBEAT_CLOSE_URL` 등록 (API로 실제 ping_url·cron 스케쥴 일치 확인, 2026-08-04) — 알림 채널은 이메일만 연결됨, Grafana Cloud 연동은 미완료
- [ ] kista-infra 배포 **전에** 구 `kista-api-caddy`/`kista-api-redis` 컨테이너 정리: `docker rm -f kista-api-caddy kista-api-redis` (포트 80/443 선점 해제, 신규 kista-infra caddy가 대신 기동)
- [ ] Fly.io 1~2일 유지 후 종료

## Fly.io 롤백

`fly-deploy.yml` workflow_dispatch로 수동 실행 가능 — 커트오버 후 1~2일간 유지.
