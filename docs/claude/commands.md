## 자주 쓰는 명령어

```bash
# 빌드
./gradlew bootJar                          # build/libs/app.jar 생성

# 초기 환경 설정
cp .env.example .env                       # 환경변수 파일 복사 후 값 입력 필요

# 테스트
./gradlew test                             # 전체 테스트 (병렬 실행)
./gradlew compileJava                      # 컴파일만 빠르게 확인 (테스트 없이)
./gradlew test --tests "com.kista.architecture.*"   # ArchUnit 규칙 테스트만
./gradlew test --tests "com.kista.domain.*"         # 도메인 단위 테스트만
./gradlew test --tests "com.kista.adapter.out.kis.*" # KIS Adapter 단위 테스트만
./gradlew test --rerun-tasks               # 캐시 무시하고 강제 재실행 (UP-TO-DATE 우회)
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'

# 실행
./gradlew bootRun --args='--spring.profiles.active=local'
docker-compose up -d                       # 앱 + PostgreSQL + Prometheus + Grafana
docker-compose up -d postgres              # DB만 기동 (로컬 개발 시)
docker compose build <service> && docker compose up -d --force-recreate <service>  # 설정 변경 후 이미지 재빌드 + 컨테이너 강제 재생성
```
