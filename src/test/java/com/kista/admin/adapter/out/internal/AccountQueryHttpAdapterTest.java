package com.kista.admin.adapter.out.internal;

import com.kista.admin.domain.model.AdminAccountView;
import com.kista.sharedkernel.Broker;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountQueryHttpAdapterTest {

    private MockWebServer server;
    private AccountQueryHttpAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).build();
        adapter = new AccountQueryHttpAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void findAll_from_to_없으면_쿼리파라미터_없이_요청한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body("[]").build());

        List<AdminAccountView> result = adapter.findAll(null, null);

        assertThat(result).isEmpty();
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/accounts");
    }

    @Test
    void findAll_from_to를_쿼리파라미터로_전달한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body("[]").build());
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);

        adapter.findAll(from, to);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).contains("from=2026-01-01");
        assertThat(recorded.getTarget()).contains("to=2026-01-31");
    }

    @Test
    void findAll_응답을_필드별로_역직렬화한다() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    [{"id":"%s","userId":"%s","accountNo":"74420614-01","broker":"KIS","createdAt":"2026-07-01T00:00:00Z"}]
                    """.formatted(id, userId))
                .build());

        List<AdminAccountView> result = adapter.findAll(null, null);

        assertThat(result).hasSize(1);
        AdminAccountView view = result.get(0);
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.accountNo()).isEqualTo("74420614-01");
        assertThat(view.broker()).isEqualTo(Broker.KIS);
    }

    @Test
    void findById_정상_응답은_Optional로_감싼다() {
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"%s","userId":"%s","accountNo":"74420614-01","broker":"KIS","createdAt":"2026-07-01T00:00:00Z"}
                    """.formatted(id, UUID.randomUUID()))
                .build());

        Optional<AdminAccountView> result = adapter.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
    }

    // trading-core의 AccountInternalController.findAccount가 계좌 없음 시 NoSuchElementException
    // (→404)을 던진다 — 어댑터가 이를 Optional.empty()로 되돌리지 못하면 예외가 그대로 전파된다.
    @Test
    void findById_404_응답은_Optional_empty로_변환된다() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        Optional<AdminAccountView> result = adapter.findById(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void countAll_전체_계좌_수를_조회한다() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200).addHeader("Content-Type", "application/json").body("7").build());

        long result = adapter.countAll();

        assertThat(result).isEqualTo(7L);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/internal/accounts/count");
    }
}
