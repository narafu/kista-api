package com.kista.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// IAE·ISE 메시지는 GENERIC 매핑이 원문 그대로 ProblemDetail detail로 내보낸다(modules/api/web.md "ProblemDetail 응답 계약").
// application·domain 패키지의 리터럴 메시지가 합쇼체 완전문장("~니다.")으로 끝나는지 소스 스캔으로 고정한다.
class ExceptionMessageFormatTest {

    // 스캔 대상 소스 루트(루트 test workingDir = 루트 프로젝트)
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src/main/java"), Path.of("trading-core/src/main/java"), Path.of("shared/src/main/java"));
    // 동결된 기존 위반 목록 — 고치면 이 파일에서도 지운다(stale 항목은 실패)
    private static final Path ALLOWLIST = Path.of("src/test/resources/architecture/exception-message-allowlist.txt");
    private static final Pattern THROW_SITE = Pattern.compile("new (IllegalArgumentException|IllegalStateException)\\(");
    private static final Pattern LAYER_PATH = Pattern.compile("/(application|domain)/");

    @Test
    void application_and_domain_exception_messages_end_with_formal_sentence() throws IOException {
        Set<String> violations = scanViolations();
        Set<String> allowlist = readAllowlist();

        Set<String> added = new TreeSet<>(violations);
        added.removeAll(allowlist);
        Set<String> stale = new TreeSet<>(allowlist);
        stale.removeAll(violations);

        assertThat(added)
                .as("IAE/ISE 메시지는 \"~니다.\"로 끝나는 한국어 완전문장이어야 하고 뒤에 값을 이어 붙이지 않는다. "
                        + "내부값(enum 원문·UUID)도 싣지 않는다 — 부재는 NoSuchElementException(고정 문구)")
                .isEmpty();
        assertThat(stale).as("고쳐진 위반은 %s에서 지운다", ALLOWLIST).isEmpty();
    }

    // 검사기 자체 고정 — 삼항·연결·formatted·cause 인자 판정
    @Test
    void detector_judges_message_shapes() {
        assertThat(violationsIn("throw new IllegalStateException(\"이미 일시중지된 전략입니다.\");")).isEmpty();
        assertThat(violationsIn("throw new IllegalArgumentException(\"최소 $%s 이상이 필요합니다.\".formatted(String.format(\"%,.2f\", x)));")).isEmpty();
        assertThat(violationsIn("throw new IllegalArgumentException(\"값은 \" + n + \"개입니다.\", cause);")).isEmpty();
        assertThat(violationsIn("throw new IllegalArgumentException(msg);")).isEmpty();
        assertThat(violationsIn("throw new IllegalStateException(String.format(Locale.US,\n \"금액 %.2f 이상이 필요합니다.\", x));")).isEmpty();
        assertThat(violationsIn("throw new IllegalStateException(String.format(Locale.US,\n \"금액 %.2f 미만입니다\", x));")).hasSize(1);
        assertThat(violationsIn("throw new IllegalStateException(String.format(\"금액 %.2f 미만입니다.\", x) + id);")).hasSize(1);
        assertThat(violationsIn("throw new IllegalArgumentException(\"값 %s입니다.\".formatted(x) + id);")).hasSize(1);
        assertThat(violationsIn("throw new IllegalArgumentException(\"마침표 없음입니다\");")).hasSize(1);
        assertThat(violationsIn("throw new IllegalArgumentException(\"DCA 전략이 아닙니다: \" + id);")).hasSize(1);
        assertThat(violationsIn("throw new IllegalArgumentException(\"잘못된 값입니다.\" + id);")).hasSize(1);
        assertThat(violationsIn("throw new IllegalArgumentException(c ? \"정수여야 합니다.\"\n : \"소수여야 합니다\");")).hasSize(1);
    }

    private static Set<String> scanViolations() throws IOException {
        Set<String> result = new TreeSet<>();
        for (Path root : SOURCE_ROOTS) {
            assertThat(root).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> LAYER_PATH.matcher(p.toString().replace('\\', '/')).find())
                        .forEach(p -> violationsIn(read(p)).forEach(msg ->
                                result.add(root.relativize(p).toString().replace('\\', '/') + "\t" + msg)));
            }
        }
        return result;
    }

    // 소스 텍스트에서 위반 throw 지점의 메시지 키(리터럴 원문을 " … "로 연결)를 모은다
    private static List<String> violationsIn(String source) {
        List<String> result = new ArrayList<>();
        Matcher m = THROW_SITE.matcher(source);
        while (m.find()) {
            String args = source.substring(m.end(), closingParen(source, m.end()));
            String message = splitTopLevel(args, ',').getFirst();
            List<String> branches = ternaryBranches(message);
            boolean violated = false;
            List<String> literals = new ArrayList<>();
            for (String branch : branches) {
                branch = unwrapStringFormat(branch);
                List<Literal> top = topLevelLiterals(branch);
                if (top.isEmpty()) continue; // 리터럴 없는 메시지(변수·getMessage)는 대상 아님
                top.forEach(l -> literals.add(l.text()));
                Literal last = top.getLast();
                // 마지막 리터럴 뒤 depth 0의 + (리터럴.formatted(x) + y 포함)
                boolean concatenatedAfter = splitTopLevel(branch.substring(last.end()), '+').size() > 1;
                if (!last.text().endsWith("니다.") || concatenatedAfter) violated = true;
            }
            if (violated) result.add(String.join(" … ", literals));
        }
        return result;
    }

    // 문자열·문자 리터럴을 건너뛰며 여는 괄호 직후 위치에서 짝 닫는 괄호 위치를 찾는다
    private static int closingParen(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') i = skipQuoted(s, i);
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        throw new IllegalStateException("닫는 괄호를 찾지 못했습니다.");
    }

    private static int skipQuoted(String s, int start) {
        char q = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') i++;
            else if (s.charAt(i) == q) return i;
        }
        return s.length();
    }

    // 괄호·리터럴 밖(depth 0)의 구분자로 자른다
    private static List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') i = skipQuoted(s, i);
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == sep && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    // "cond ? a : b"면 [a, b], 아니면 [식 전체]
    private static List<String> ternaryBranches(String expr) {
        List<String> q = splitTopLevel(expr, '?');
        if (q.size() < 2) return List.of(expr);
        return splitTopLevel(String.join("?", q.subList(1, q.size())), ':');
    }

    // String.format([Locale,] "템플릿", ...) 꼬리이면 템플릿 인자 + 호출 뒤 꼬리(+ y 검사용)만 남긴다
    private static String unwrapStringFormat(String expr) {
        String s = expr.strip();
        String prefix = "String.format(";
        if (!s.startsWith(prefix)) return expr;
        int close = closingParen(s, prefix.length());
        String template = splitTopLevel(s.substring(prefix.length(), close), ',').stream()
                .filter(a -> !topLevelLiterals(a).isEmpty())
                .findFirst().orElse("");
        return template + s.substring(close + 1);
    }

    private record Literal(String text, int end) {
    }

    // depth 0의 문자열 리터럴만 — String.format 인자 등 괄호 안 리터럴은 메시지 본문이 아니다
    private static List<Literal> topLevelLiterals(String s) {
        List<Literal> result = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                int end = skipQuoted(s, i);
                if (depth == 0) result.add(new Literal(s.substring(i + 1, end), end + 1));
                i = end;
            } else if (c == '\'') i = skipQuoted(s, i);
            else if (c == '(') depth++;
            else if (c == ')') depth--;
        }
        return result;
    }

    private static Set<String> readAllowlist() throws IOException {
        if (!Files.exists(ALLOWLIST)) return Set.of();
        Set<String> result = new TreeSet<>();
        for (String line : Files.readAllLines(ALLOWLIST, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) result.add(line);
        }
        return result;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
