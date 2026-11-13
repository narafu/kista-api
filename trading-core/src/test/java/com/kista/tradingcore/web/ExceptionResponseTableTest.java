package com.kista.tradingcore.web;

import com.kista.shared.kernel.AppErrorRaisedEvent;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.broker.domain.model.BrokerWithdrawalEnabledException;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.tradingcore.trading.domain.model.AlreadyOrderedTodayException;
import com.kista.tradingcore.trading.domain.model.DcaManualExecutionUnsupportedException;
import com.kista.tradingcore.trading.domain.model.DuplicateStrategyTickerException;
import com.kista.tradingcore.trading.domain.model.ManualTradingException;
import com.kista.tradingcore.trading.domain.model.ManualTradingFailedException;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
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
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// trading-core 프로세스의 예외→HTTP 응답 표 — 컨트롤러가 던진 예외가 MVC 예외 해석을 거쳐 어떤 status·title·code·detail이 되고
// 오류 보고(AppErrorRaisedEvent)가 나가는지 고정한다. 처리기 클래스를 참조하지 않아 처리기 구조를 바꿔도 같은 표로 검증한다
@WebMvcTest(controllers = ExceptionResponseTableTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ExceptionResponseTableTest.ThrowingController.class)
@RecordApplicationEvents
@Execution(ExecutionMode.SAME_THREAD)
class ExceptionResponseTableTest {

    private static final String INTERNAL_ERROR_DETAIL = "예기치 않은 오류가 발생했습니다.";

    // 예외 1종의 기대 응답 — code·detail null이면 응답에 키 없음(빈 detail도 직렬화에서 빠진다) / reported는 오류 보고 이벤트 발행 여부
    record Row(String name, Supplier<Exception> exception, int status, String title, String code, String detail, boolean reported) {
        @Override
        public String toString() { return name; }
    }

    static final List<Row> ROWS = List.of(
            // 증권사 자격증명·한도
            new Row("BrokerWithdrawalEnabled", BrokerWithdrawalEnabledException::new, 422, "Broker Withdrawal Enabled", "BROKER_WITHDRAWAL_ENABLED", new BrokerWithdrawalEnabledException().getMessage(), false),
            new Row("BrokerIpNotAllowed", BrokerIpNotAllowedException::new, 422, "Broker IP Not Allowed", "BROKER_IP_NOT_ALLOWED", new BrokerIpNotAllowedException().getMessage(), false),
            new Row("BrokerPermissionDenied", BrokerPermissionDeniedException::new, 422, "Broker Permission Denied", "BROKER_PERMISSION_DENIED", new BrokerPermissionDeniedException().getMessage(), false),
            new Row("BrokerCredential", BrokerCredentialException::new, 422, "Invalid Broker Credentials", "BROKER_CREDENTIAL_INVALID", new BrokerCredentialException().getMessage(), false),
            new Row("BrokerRateLimit", BrokerRateLimitException::new, 429, "Broker Rate Limit", "BROKER_RATE_LIMITED", new BrokerRateLimitException().getMessage(), false),
            // 증권사 API 실패 — 벤더 표기 title, 고정 문구, 오류 보고
            new Row("BrokerApi", () -> new StubBrokerApiException("Toss", "원본 응답 바디"), 503, "Toss API Error", "BROKER_UNAVAILABLE",
                    "증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요.", true),
            // 매매·전략 409
            new Row("AlreadyOrderedToday", AlreadyOrderedTodayException::new, 409, "Conflict", "ALREADY_ORDERED_TODAY", new AlreadyOrderedTodayException().getMessage(), false),
            new Row("DcaManualExecutionUnsupported", DcaManualExecutionUnsupportedException::new, 409, "Conflict", "DCA_MANUAL_EXECUTION_UNSUPPORTED", new DcaManualExecutionUnsupportedException().getMessage(), false),
            new Row("ManualTrading", () -> new ManualTradingException("주문 불가 상태입니다."), 409, "Conflict", null, "주문 불가 상태입니다.", false),
            // 바로주문 비증권사 실패 — 500이지만 보고는 ManualTradingService가 따로 하므로 처리기는 보고하지 않는다
            new Row("ManualTradingFailed", () -> new ManualTradingFailedException("주문", new RuntimeException("x")), 500, "Manual Trading Failed", null,
                    "주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", false),
            new Row("OrderCancel", () -> new OrderCancelException("취소 가능한 상태가 아닙니다."), 409, "Conflict", "ORDER_NOT_CANCELLABLE", "취소 가능한 상태가 아닙니다.", false),
            new Row("PrivacyTradeConflict", () -> new PrivacyTradeConflictException("이미 있습니다."), 409, "Conflict", null, "이미 있습니다.", false),
            new Row("DuplicateStrategyTicker", DuplicateStrategyTickerException::new, 409, "Conflict", null, new DuplicateStrategyTickerException().getMessage(), false),
            new Row("DuplicateAccount", () -> new Account.DuplicateAccountException("12345678"), 409, "Conflict", "DUPLICATE_ACCOUNT",
                    new Account.DuplicateAccountException("12345678").getMessage(), false),
            // 범용 JDK 예외(GENERIC)
            new Row("Security", () -> new SecurityException("본인 계좌가 아닙니다."), 403, "Access Denied", "ACCESS_DENIED", "본인 계좌가 아닙니다.", false),
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

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Body body) {}

        @GetMapping("/test/param")
        int param(@RequestParam int n) { return n; }
    }
}
