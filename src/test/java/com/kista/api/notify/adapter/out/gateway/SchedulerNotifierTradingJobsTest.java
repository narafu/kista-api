package com.kista.api.notify.adapter.out.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// SchedulerNotifier.TRADING_JOBS가 trading-core의 실제 잡 이름과 어긋나지 않게 잠근다 — root main은 trading-core를 참조할 수 없어
// 소스에서 SchedulerJobRunner.run(...)·runBatch(...)의 이름 리터럴을 긁어 대조한다(테스트 작업 디렉토리는 루트 프로젝트)
class SchedulerNotifierTradingJobsTest {

    private static final Pattern LITERAL_JOB = Pattern.compile("(?:jobRunner\\.run|runBatch)\\(\\s*\"([^\"]+)\"");
    private static final Pattern ANY_JOB_CALL = Pattern.compile("jobRunner\\.run\\(");
    private static final Pattern INJECTED_RUNNER = Pattern.compile("SchedulerJobRunner\\s+(\\w+)\\s*[;,)]"); // 필드·생성자 파라미터 선언
    private static final int DYNAMIC_NAME_CALLS = 1; // TradingCloseScheduler.runBatch(name, ...) 한 곳 — 이름은 runBatch 리터럴로 수집된다

    @Test
    void 필터는_trading_core_잡_이름과_정확히_같다() {
        String sources = read(Path.of("trading-core/src/main/java"));

        assertThat(literalJobs(sources)).isEqualTo(SchedulerNotifier.TRADING_JOBS);
        // 이름을 변수로 넘기는 호출이 늘면 위 수집이 놓친다 — 늘었으면 이 테스트와 필터를 함께 고친다
        assertThat(count(ANY_JOB_CALL, sources) - count(Pattern.compile("jobRunner\\.run\\(\\s*\""), sources)).isEqualTo(DYNAMIC_NAME_CALLS);
        // 수집 정규식이 필드명 jobRunner에 기대므로 다른 이름으로 주입하면 놓친다 — 주입 이름을 jobRunner 하나로 잠근다
        assertThat(INJECTED_RUNNER.matcher(sources).results().map(r -> r.group(1)).collect(Collectors.toSet())).containsOnly("jobRunner");
    }

    @Test
    void root_잡은_필터에_걸리지_않는다() {
        assertThat(literalJobs(read(Path.of("src/main/java")))).doesNotContainAnyElementsOf(SchedulerNotifier.TRADING_JOBS);
    }

    private static Set<String> literalJobs(String sources) {
        Matcher m = LITERAL_JOB.matcher(sources);
        return m.results().map(r -> r.group(1)).collect(Collectors.toSet());
    }

    private static long count(Pattern pattern, String sources) {
        return pattern.matcher(sources).results().count();
    }

    // 디렉토리 아래 .java 전부를 하나의 문자열로
    private static String read(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).map(p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
