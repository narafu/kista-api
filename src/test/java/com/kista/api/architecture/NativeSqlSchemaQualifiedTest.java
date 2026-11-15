package com.kista.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 두 프로세스를 한 커넥션 풀로 합치면 search_path가 하나뿐이라 public·trading의 동명 테이블(scheduler_locks 등)이 갈린다
// → main의 native SQL·JdbcTemplate SQL은 테이블에 스키마를 붙인다(테스트 SQL은 대상 아님). 스캔: 세 서브프로젝트 main 소스 문자열 리터럴
class NativeSqlSchemaQualifiedTest {

    private static final List<String> SOURCE_ROOTS =
            List.of("src/main/java", "trading-core/src/main/java", "shared/src/main/java");
    // native SQL을 쓰는 파일 표지
    private static final Pattern NATIVE_MARKER = Pattern.compile("nativeQuery\\s*=\\s*true|@NativeQuery|createNativeQuery|JdbcTemplate|JdbcClient");
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern CONCAT = Pattern.compile("\"\\s*\\+\\s*\"");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern SQL_VERB = Pattern.compile("(?i)\\b(select|insert|update|delete)\\b");
    // 키워드는 대소문자 무시, 식별자는 소문자 snake만(JPQL 엔티티 CamelCase 배제)
    private static final Pattern TABLE_REF = Pattern.compile("(?i:\\b(?:from|join|into|update))\\s+([a-z_][a-z0-9_]*)\\b(?![.(])");
    private static final Pattern CTE = Pattern.compile("(?i)(?:\\bwith(?:\\s+recursive)?|,)\\s*([a-z_][a-z0-9_]*)\\s+as\\s*\\(");
    // 테이블이 아닌 토큰: DO UPDATE SET, JOIN LATERAL, FROM ONLY
    private static final Set<String> NOT_TABLE = Set.of("set", "lateral", "only", "select");

    @Test
    void nativeSqlTables_mustBeSchemaQualified() throws IOException {
        List<String> violations = new ArrayList<>();
        int scannedSql = 0;
        for (String root : SOURCE_ROOTS) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    if (!NATIVE_MARKER.matcher(source).find()) {
                        continue;
                    }
                    for (String sql : sqlLiterals(source)) {
                        scannedSql++;
                        violations.addAll(unqualifiedTables(sql).stream().map(t -> file + " -> " + t).toList());
                    }
                }
            }
        }
        // 스캔 대상이 비면 규칙이 조용히 통과하므로 SQL이 실제로 잡히는지 확인
        assertThat(scannedSql).isPositive();
        assertThat(violations).as("스키마 없는 native SQL 테이블(예: trading.orders로 한정)").isEmpty();
    }

    // 파일의 SQL 문자열 — text block을 먼저 떼고 + 연결 리터럴을 합친 뒤 SQL 동사가 있는 것만
    private static List<String> sqlLiterals(String source) {
        List<String> literals = new ArrayList<>();
        Matcher block = TEXT_BLOCK.matcher(source);
        while (block.find()) {
            literals.add(block.group(1));
        }
        String rest = CONCAT.matcher(TEXT_BLOCK.matcher(source).replaceAll("\"\"")).replaceAll("");
        Matcher str = STRING.matcher(rest);
        while (str.find()) {
            literals.add(str.group(1));
        }
        return literals.stream().filter(s -> SQL_VERB.matcher(s).find()).toList();
    }

    private static List<String> unqualifiedTables(String sql) {
        Set<String> ctes = new HashSet<>();
        Matcher cte = CTE.matcher(sql);
        while (cte.find()) {
            ctes.add(cte.group(1).toLowerCase());
        }
        List<String> found = new ArrayList<>();
        Matcher ref = TABLE_REF.matcher(sql);
        while (ref.find()) {
            String name = ref.group(1);
            if (!NOT_TABLE.contains(name.toLowerCase()) && !ctes.contains(name)) {
                found.add(name);
            }
        }
        return found;
    }
}
