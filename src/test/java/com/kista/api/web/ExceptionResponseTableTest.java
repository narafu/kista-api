package com.kista.api.web;

import com.kista.api.admin.domain.model.AdminBrokerCredentialException;
import com.kista.api.admin.domain.model.AdminBrokerRateLimitException;
import com.kista.api.admin.domain.model.AdminPrivacyTradeConflictException;
import com.kista.api.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceBudget;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceGroup;
import com.kista.api.finance.domain.model.FinanceGroupInvitation;
import com.kista.api.finance.domain.model.MonthlyClosing;
import com.kista.api.market.domain.model.CandleUnavailableException;
import com.kista.api.user.domain.auth.InvalidRefreshTokenException;
import com.kista.api.user.domain.model.User;
import com.kista.shared.kernel.AppErrorRaisedEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// root 프로세스의 예외→HTTP 응답 표 — 컨트롤러가 던진 예외가 MVC 예외 해석을 거쳐 어떤 status·title·code·detail이 되고
// 오류 보고(AppErrorRaisedEvent)가 나가는지 고정한다. 처리기 클래스를 참조하지 않아 처리기 구조를 바꿔도 같은 표로 검증한다
@WebMvcTest(controllers = ExceptionResponseTableTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ExceptionResponseTableTest.ThrowingController.class)
@RecordApplicationEvents
@Execution(ExecutionMode.SAME_THREAD)
class ExceptionResponseTableTest {

    private static final String INTERNAL_ERROR_DETAIL = "예기치 않은 오류가 발생했습니다.";
    private static final Instant RETRY_AFTER = Instant.parse("2026-11-14T00:00:00Z"); // 쿨다운 재신청 가능 시각

    // 예외 1종의 기대 응답 — code·detail null이면 응답에 키 없음(빈 detail도 직렬화에서 빠진다) / reported는 오류 보고 이벤트 발행 여부
    record Row(String name, Supplier<Exception> exception, int status, String title, String code, String detail, boolean reported) {
        @Override
        public String toString() { return name; }
    }

    static final List<Row> ROWS = List.of(
            new Row("InvalidRefreshToken", () -> new InvalidRefreshTokenException("토큰이 만료되었습니다."), 401, "Unauthorized", null, "토큰이 만료되었습니다.", false),
            // 내부 API 응답에서 복원한 own-type
            new Row("AdminBrokerCredential", AdminBrokerCredentialException::new, 422, "Invalid Broker Credentials", "BROKER_CREDENTIAL_INVALID",
                    new AdminBrokerCredentialException().getMessage(), false),
            new Row("AdminBrokerRateLimit", AdminBrokerRateLimitException::new, 429, "Broker Rate Limit", "BROKER_RATE_LIMITED",
                    new AdminBrokerRateLimitException().getMessage(), false),
            new Row("TradingPolicyUnavailable", () -> new TradingPolicyUnavailableException(new RuntimeException("x")), 503, "Trading Core Unavailable",
                    "TRADING_CORE_UNAVAILABLE", new TradingPolicyUnavailableException(new RuntimeException("x")).getMessage(), false),
            new Row("CandleUnavailable", () -> new CandleUnavailableException("캔들을 불러오지 못했습니다."), 503, "Broker API Error", "BROKER_UNAVAILABLE",
                    "캔들을 불러오지 못했습니다.", false),
            new Row("AdminPrivacyTradeConflict", () -> new AdminPrivacyTradeConflictException("이미 있습니다."), 409, "Conflict", null, "이미 있습니다.", false),
            // 사용자·가계부 409
            new Row("UserAlreadyActive", User.AlreadyActiveException::new, 409, "Conflict", null, new User.AlreadyActiveException().getMessage(), false),
            new Row("BudgetOverlappingPeriod", () -> new FinanceBudget.OverlappingPeriodException("기간이 겹칩니다."), 409, "Conflict", null, "기간이 겹칩니다.", false),
            new Row("DuplicateAccountNo", () -> new FinanceAccount.DuplicateAccountNoException("1234"), 409, "Conflict", null,
                    new FinanceAccount.DuplicateAccountNoException("1234").getMessage(), false),
            new Row("LinkedAssetSnapshots", FinanceAccount.LinkedAssetSnapshotsException::new, 409, "Conflict", null,
                    new FinanceAccount.LinkedAssetSnapshotsException().getMessage(), false),
            new Row("DuplicateCategoryName", () -> new FinanceCategory.DuplicateNameException("식비"), 409, "Conflict", null,
                    new FinanceCategory.DuplicateNameException("식비").getMessage(), false),
            new Row("InvalidInvitationState", () -> new FinanceGroupInvitation.InvalidInvitationStateException("만료된 초대입니다."), 409, "Conflict", null,
                    "만료된 초대입니다.", false),
            new Row("MembershipConflict", () -> new FinanceGroup.MembershipConflictException("이미 소속입니다."), 409, "Conflict", null, "이미 소속입니다.", false),
            new Row("MonthClosed", () -> new MonthlyClosing.MonthClosedException("2026-10"), 409, "Conflict", "MONTH_CLOSED",
                    new MonthlyClosing.MonthClosedException("2026-10").getMessage(), false),
            // 범용 JDK 예외(GENERIC)
            new Row("Security", () -> new SecurityException("본인 자료가 아닙니다."), 403, "Access Denied", "ACCESS_DENIED", "본인 자료가 아닙니다.", false),
            new Row("IllegalState", () -> new IllegalStateException("상태가 맞지 않습니다."), 400, "Invalid State", null, "상태가 맞지 않습니다.", false),
            new Row("IllegalArgument", () -> new IllegalArgumentException("값이 틀렸습니다."), 400, "Invalid Request", null, "값이 틀렸습니다.", false),
            new Row("NoSuchElement", () -> new NoSuchElementException("id=abc"), 404, "Resource Not Found", null, "요청한 정보를 찾을 수 없습니다.", false),
            new Row("DateTimeParse", () -> {
                try { LocalDate.parse("2026-13-01"); return null; } catch (Exception e) { return e; }
            }, 400, "Invalid Date Format", null, "날짜 형식이 올바르지 않습니다.", false),
            // 미매핑 — 500 + 보고, 클라이언트 이탈 — 503 빈 detail + 미보고
            new Row("Unmapped", () -> new RuntimeException("boom"), 500, "Internal Server Error", null, INTERNAL_ERROR_DETAIL, true),
            new Row("ClientDisconnect", () -> new RuntimeException(new IOException("Broken pipe")), 503, "Client Disconnected", null, null, false)
    );

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationEvents events;

    static List<Row> rows() { return ROWS; }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void thrown_exception_maps_to_table_row(Row row) throws Exception {
        ResultActions result = mockMvc.perform(get("/test/throw/{index}", ROWS.indexOf(row)));

        expectProblem(result, row.status(), row.title(), row.code(), row.detail());
        assertThat(events.stream(AppErrorRaisedEvent.class).count()).isEqualTo(row.reported() ? 1 : 0);
    }

    @Test
    void cooldown_maps_to_429_with_retry_after_header_and_property() throws Exception {
        ResultActions result = mockMvc.perform(get("/test/cooldown"));

        expectProblem(result, 429, "Cooldown Active", "COOLDOWN_ACTIVE", new User.CooldownException(RETRY_AFTER).getMessage());
        result.andExpect(header().string(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER.getEpochSecond())))
                .andExpect(jsonPath("$.retryAfter").value(RETRY_AFTER.toString()));
        assertThat(events.stream(AppErrorRaisedEvent.class).count()).isZero();
    }

    @Test
    void validation_failure_maps_to_400_with_field_messages() throws Exception {
        ResultActions result = mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"));

        expectProblem(result, 400, "Validation Failed", null, "[name: 이름은 필수입니다.]");
        assertThat(events.stream(AppErrorRaisedEvent.class).count()).isZero();
    }

    @Test
    void framework_request_errors_map_without_report() throws Exception {
        // 바디 파싱 실패 / 필수 파라미터 누락 / 타입 불일치 / 없는 경로 / 미지원 메서드
        expectProblem(mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{")),
                400, "Malformed Request", null, "요청 형식이 올바르지 않습니다.");
        expectProblem(mockMvc.perform(get("/test/param")), 400, "Bad Request", null, "필수 요청 값이 누락되었습니다.");
        expectProblem(mockMvc.perform(get("/test/param").param("n", "abc")), 400, "Bad Request", null, "요청 값의 형식이 올바르지 않습니다.");
        expectProblem(mockMvc.perform(get("/no-such-path")), 404, "Not Found", null, "요청한 경로를 찾을 수 없습니다.");
        expectProblem(mockMvc.perform(post("/test/param")), 405, "Method Not Allowed", null, "지원하지 않는 요청 방식입니다.");
        assertThat(events.stream(AppErrorRaisedEvent.class).count()).isZero();
    }

    private static void expectProblem(ResultActions result, int status, String title, String code, String detail) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(detail == null ? jsonPath("$.detail").doesNotExist() : jsonPath("$.detail").value(detail))
                .andExpect(code == null ? jsonPath("$.code").doesNotExist() : jsonPath("$.code").value(code));
    }

    // 표의 예외를 그대로 던지는 테스트 전용 컨트롤러(테스트 클래스 중첩이라 스캔 대상이 아니다 — @Import로만 올린다)
    @RestController
    static class ThrowingController {

        record Body(@NotBlank(message = "이름은 필수입니다.") String name) {}

        @GetMapping("/test/throw/{index}")
        void throwRow(@PathVariable int index) throws Exception {
            throw ROWS.get(index).exception().get();
        }

        @GetMapping("/test/cooldown")
        void cooldown() {
            throw new User.CooldownException(RETRY_AFTER);
        }

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Body body) {}

        @GetMapping("/test/param")
        int param(@RequestParam int n) { return n; }
    }
}
