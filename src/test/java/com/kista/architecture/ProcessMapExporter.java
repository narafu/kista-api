package com.kista.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

// 업무 흐름 맵(flows.yml) + @Scheduled 시간표를 단일 HTML(build/spring-modulith-docs/process.html)로 내보낸다
final class ProcessMapExporter {

    private static final Path OUTPUT = Path.of("build/spring-modulith-docs/process.html"); // modules.html 옆에 둔다(상호 링크)
    private static final String TEMPLATE = "/architecture/process-map.html"; // 데이터 자리표시자를 가진 템플릿
    private static final String FLOWS = "/architecture/flows.yml"; // 사람이 작성하는 흐름 원본
    private static final String PLACEHOLDER = "/*__DATA__*/null"; // 템플릿 안 JSON 삽입 지점
    private static final ZoneId KST = ZoneId.of("Asia/Seoul"); // 타임라인 축 기준
    private static final ZonedDateTime CRON_FROM = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, KST); // 결정적 출력을 위한 고정 시작점
    private static final int CRON_SCAN_DAYS = 400; // 연 1회 cron까지 잡히도록 1년 넘게 훑는다
    private static final Map<String, String> DOW = Map.of("MON", "월", "TUE", "화", "WED", "수", "THU", "목",
            "FRI", "금", "SAT", "토", "SUN", "일");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // flows.yml 키 오타를 조용히 버리지 않는다
            .build();

    private ProcessMapExporter() {
    }

    record FlowMap(List<Lane> lanes, List<LandscapeGroup> landscape, Map<String, Flow> flows) {
        FlowMap {
            lanes = lanes == null ? List.of() : lanes;
            landscape = landscape == null ? List.of() : landscape;
            flows = flows == null ? Map.of() : flows;
        }
    }

    record Lane(String id, String label) {
    }

    record LandscapeGroup(String group, List<LandscapeStep> steps) {
        LandscapeGroup {
            steps = steps == null ? List.of() : steps;
        }
    }

    // flow: 연결된 상세 흐름 id — 없으면 "준비 중"
    record LandscapeStep(String id, String title, String when, String flow) {
    }

    record Flow(String title, String trigger, String summary, List<Step> steps) {
        Flow {
            steps = steps == null ? List.of() : steps;
        }
    }

    // to: 호출·기록하는 상대 레인, cond: 실행 조건, state: 상태 변화, fail: 실패 경로
    record Step(String id, String lane, List<String> to, String title, String desc, List<String> code,
                String cond, String state, String fail) {
        Step {
            to = to == null ? List.of() : to;
            code = code == null ? List.of() : code;
        }
    }

    // process: 실행 프로세스(컨테이너 role), schedule: cron 원문 또는 주기, times: KST HH:mm (주기 실행은 빈 목록)
    record Job(String process, String name, String module, String schedule, String days, List<String> times) {
    }

    record Data(FlowMap map, List<Job> jobs) {
    }

    static FlowMap load() {
        try (InputStream in = ProcessMapExporter.class.getResourceAsStream(FLOWS)) {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false); // 중복 flow·step 키가 앞의 것을 조용히 덮어쓰지 않도록
            Object yaml = new Yaml(options).load(in);
            return JSON.convertValue(yaml, FlowMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // flows.yml이 코드·자기 자신과 어긋난 지점 — 비어 있어야 한다
    static List<String> violations(FlowMap map, JavaClasses classes) {
        var errors = new ArrayList<String>();
        var laneIds = map.lanes().stream().map(Lane::id).collect(Collectors.toSet());

        // 랜드스케이프 → 흐름 연결
        map.landscape().stream().flatMap(g -> g.steps().stream())
                .filter(s -> s.flow() != null && !map.flows().containsKey(s.flow()))
                .forEach(s -> errors.add("landscape " + s.id() + ": 없는 flow '" + s.flow() + "'"));

        map.flows().forEach((flowId, flow) -> {
            var stepIds = new HashSet<String>();
            for (Step step : flow.steps()) {
                var where = flowId + "." + step.id();
                if (!stepIds.add(step.id())) {
                    errors.add(where + ": step id 중복");
                }
                if (!laneIds.contains(step.lane())) {
                    errors.add(where + ": 없는 lane '" + step.lane() + "'");
                }
                step.to().stream().filter(t -> !laneIds.contains(t))
                        .forEach(t -> errors.add(where + ": 없는 to lane '" + t + "'"));
                step.code().forEach(ref -> codeError(ref, classes).ifPresent(e -> errors.add(where + ": " + e)));
            }
        });
        return errors;
    }

    // "Class#method" 또는 "Class" — 클래스는 단순 이름(유일) 또는 FQCN
    private static Optional<String> codeError(String ref, JavaClasses classes) {
        var parts = ref.split("#", 2);
        var name = parts[0];
        var matches = classes.stream()
                .filter(c -> name.contains(".") ? c.getName().equals(name) : c.getSimpleName().equals(name))
                .toList();
        if (matches.size() != 1) {
            return Optional.of("'" + ref + "' 클래스 " + (matches.isEmpty() ? "없음" : "이름 중복 — FQCN으로 쓸 것: " + matches));
        }
        if (parts.length == 2 && matches.getFirst().getAllMethods().stream().noneMatch(m -> m.getName().equals(parts[1]))) {
            return Optional.of("'" + ref + "' 메서드 없음");
        }
        return Optional.empty();
    }

    // @Scheduled 메서드 전수 — cron은 KST 실행 시각을, fixedDelay는 주기만 기록
    static List<Job> jobs(JavaClasses classes) {
        return classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Scheduled.class))
                .map(ProcessMapExporter::toJob)
                .sorted(Comparator.comparing(Job::process).thenComparing(j -> j.times().isEmpty() ? "99" : j.times().getFirst())
                        .thenComparing(Job::name))
                .toList();
    }

    private static Job toJob(JavaMethod method) {
        var scheduled = method.getAnnotationOfType(Scheduled.class);
        var owner = method.getOwner();
        var name = owner.getSimpleName() + "#" + method.getName();
        var module = owner.getPackageName().replaceFirst("^com\\.kista\\.", "").split("\\.")[0];
        if (scheduled.cron().isEmpty()) {
            return new Job(process(owner), name, module, period(scheduled), "주기 실행", List.of());
        }
        // 요일·날짜 라벨을 KST 축에 그대로 쓰므로 KST cron만 허용 — zone 생략은 서버 TZ 의존이라 금지
        if (!KST.equals(scheduled.zone().isEmpty() ? null : ZoneId.of(scheduled.zone()))) {
            throw new IllegalStateException(name + ": @Scheduled zone은 Asia/Seoul이어야 한다 (현재 '" + scheduled.zone() + "')");
        }
        return new Job(process(owner), name, module, scheduled.cron(), days(scheduled.cron()), times(scheduled.cron(), KST));
    }

    // fixedDelay/fixedRate(숫자 또는 *String) → "5분마다" / "30초마다", 해석 불가 문자열은 원문
    private static String period(Scheduled scheduled) {
        long delay = scheduled.fixedDelay() > 0 ? scheduled.fixedDelay() : scheduled.fixedRate();
        if (delay <= 0) {
            var raw = scheduled.fixedDelayString().isEmpty() ? scheduled.fixedRateString() : scheduled.fixedDelayString();
            return raw + " 주기";
        }
        long seconds = scheduled.timeUnit().toSeconds(delay);
        return seconds % 60 == 0 ? seconds / 60 + "분마다" : seconds + "초마다";
    }

    // 1년+ 동안의 실제 발화 시각을 KST 시:분으로 모은다 — "0,12"·"L" 같은 필드도 CronExpression이 해석
    private static List<String> times(String cron, ZoneId zone) {
        var expression = CronExpression.parse(cron);
        var end = CRON_FROM.plusDays(CRON_SCAN_DAYS);
        var result = new TreeSet<String>();
        var next = expression.next(CRON_FROM.withZoneSameInstant(zone).minusSeconds(1));
        while (next != null && next.isBefore(end)) {
            result.add(next.withZoneSameInstant(KST).format(DateTimeFormatter.ofPattern("HH:mm")));
            next = expression.next(next);
        }
        return List.copyOf(result);
    }

    // 요일·날짜 필드 → 사람이 읽는 라벨 (지원 밖 형태는 cron 원문 필드 그대로)
    private static String days(String cron) {
        var f = cron.trim().split("\\s+"); // 초 분 시 일 월 요일
        String dom = f[3], month = f[4], dow = f[5];
        if (!month.equals("*")) {
            return "매년 " + month + "/" + dom;
        }
        if (dom.equals("L")) {
            return "매월 말일";
        }
        if (!dom.equals("*") && !dom.equals("?")) {
            return "매월 " + dom + "일";
        }
        if (dow.equals("*") || dow.equals("?")) {
            return "매일";
        }
        var label = dow;
        for (var e : DOW.entrySet()) {
            label = label.replace(e.getKey(), e.getValue());
        }
        return label.replace("-", "~");
    }

    // 실행 프로세스 판별 — trading-core는 kista-trading, root는 scheduler.enabled 게이트 여부로 갈린다
    private static String process(JavaClass owner) {
        var uri = owner.getSource().map(s -> s.getUri().toString().replace('\\', '/')).orElse("");
        if (uri.contains("/trading-core/")) {
            return "kista-trading";
        }
        boolean gated = owner.isAnnotatedWith(ConditionalOnProperty.class)
                && owner.getAnnotationOfType(ConditionalOnProperty.class).prefix().equals("scheduler");
        return gated ? "kista-scheduler" : "kista-api · kista-scheduler";
    }

    static void write(FlowMap map, List<Job> jobs) {
        // </script> 조기 종료 방지 — JSON 안의 "</"를 이스케이프
        var json = JSON.writeValueAsString(new Data(map, jobs)).replace("</", "<\\/");
        try (InputStream in = ProcessMapExporter.class.getResourceAsStream(TEMPLATE)) {
            var template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Files.createDirectories(OUTPUT.getParent());
            Files.writeString(OUTPUT, template.replace(PLACEHOLDER, json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
