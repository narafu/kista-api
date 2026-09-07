---
paths: "**/*.java"
---

## 파일 인코딩 주의 (BOM)

- 서브에이전트가 Java 파일 import 수정 시 BOM(`\xef\xbb\xbf`) 삽입 버그 발생 사례 → `compileJava` 즉시 실패
- 일괄 제거: `grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '1s/^\xef\xbb\xbf//' "$f"; done`
