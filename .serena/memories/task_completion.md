# 작업 완료 체크리스트

## 코드 수정 후 필수 확인

```bash
./gradlew compileJava    # 컴파일 오류 없는지 확인 (BOM 삽입, import 오류 등)
./gradlew test           # 전체 테스트 통과 확인
```

## 동시 수정 필요 파일 쌍 체크

- 환경변수 추가/제거: `application.yml` + `.env.example` + `docker-compose.yml`
- 새 Flyway 마이그레이션: Entity + JpaRepository
- JPA Entity 컬럼 변경: Flyway SQL과 크로스체크 (`nullable`, `length`, `precision`, `scale`)
- Port 인터페이스 수정: 구현 Adapter + 테스트 Mock
- Controller에 새 UseCase 필드 추가: 해당 `@WebMvcTest`에 `@MockBean` 추가
- `SecurityConfig`에 새 Filter 추가: `@WebMvcTest` `@Import` 목록에도 추가

## 서브에이전트가 Java 파일 수정한 경우

BOM 삽입 여부 확인:
```bash
grep -rl $'\xef\xbb\xbf' src --include="*.java"
```
있으면 제거:
```bash
grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '1s/^\xef\xbb\xbf//' "$f"; done
```

## 커밋
```bash
git config user.name   # narafu 확인
git config user.email  # narafu@kakao.com 확인
git add <파일들>
git commit -m "..."
# push는 사용자가 명시적으로 요청한 경우에만
```
