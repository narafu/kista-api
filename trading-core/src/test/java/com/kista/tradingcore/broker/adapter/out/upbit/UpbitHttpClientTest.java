package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitHttpClientTest {

    private static final String BASE_URL = "https://api.upbit.com";
    private static final String ACCESS = "test-access";
    private static final String SECRET = "test-secret-key-40-chars-xxxxxxxxxxxxxxx";
    private static final String NONCE = "00000000-0000-0000-0000-000000000001";
    private static final BrokerAccountRef AUTH = new BrokerAccountRef(null, ACCESS, SECRET, null, null, Broker.UPBIT);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private UpbitHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UpbitHttpClient(builder.build(), mapper, () -> NONCE);
    }

    @Test
    void tokenMatchesIndependentHs512Vector() {
        // 기대값은 Python hmac/hashlib/base64로 독립 계산(헤더 {"alg":"HS512","typ":"JWT"}, compact JSON, 키 순서 access_key·nonce·query_hash·query_hash_alg)
        assertThat(client.token(ACCESS, SECRET, "market=KRW-BTC")).isEqualTo(
                "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3Nfa2V5IjoidGVzdC1hY2Nlc3MiLCJub25jZSI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSIsInF1ZXJ5X2hhc2giOiJiNzQ5ZGZjMmUxN2Y3NWU1YjQ2YzgxNjFmOTdmZTdjOTI5OGVkNDE2N2VhMjFjNWM5NGQxNjU3M2VmZDhhODAxMzUxNDcwYzBmZjFhOWEzZjFlNzYzZjgyNDk5NjgyMThjMDRjNTcxYzhiNDVhYTgwY2Q0NTg4ZTZjNGJlMDczOCIsInF1ZXJ5X2hhc2hfYWxnIjoiU0hBNTEyIn0.Rr2j6LBD2LHYGUskoMaVtAVU0aFGaGpjvfBJXhBxMHcrNYEznisHbvb_zqusnzMoP5PUKeSNDk8TV40CVb7WRA");
        assertThat(client.token(ACCESS, SECRET, "")).isEqualTo(
                "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3Nfa2V5IjoidGVzdC1hY2Nlc3MiLCJub25jZSI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSJ9.fPITQYWYA6Rr0lypI8HGdimnqNIAUKk6DwPtUqzqFQUSVy-pqDBBbf6xN9qCj4AEiFqzCFFv8LqRvBHhK3nopg");
    }

    @Test
    void tokenSignatureVerifiesAndHashesUnencodedQuery() throws Exception {
        String token = client.token(ACCESS, SECRET, "market=KRW-BTC&side=bid&ord_type=price&price=5000&identifier=0190a5c4-0000-7000-8000-000000000001");
        String[] parts = token.split("\\.");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("access_key").asText()).isEqualTo(ACCESS);
        assertThat(payload.get("nonce").asText()).isEqualTo(NONCE);
        assertThat(payload.get("query_hash_alg").asText()).isEqualTo("SHA512");
        // Python hashlib.sha512 독립 계산값
        assertThat(payload.get("query_hash").asText()).isEqualTo(
                "edff38f20d7c635e5ea5c0d55236e934b3286c530667e2e1dca234100f043294fe990714b757c6f54f290a7968a4188e9c6a32d212960b38cbbffef69a172319");
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        String expectedSig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertThat(parts[2]).isEqualTo(expectedSig);
    }

    @Test
    void postSendsJsonBodyAndBearerTokenHashedFromSameOrderedPairs() {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("market", "KRW-BTC");
        body.put("side", "bid");
        body.put("ord_type", "price");
        body.put("price", "5000");
        body.put("identifier", "0190a5c4-0000-7000-8000-000000000001");
        String expectedToken = client.token(ACCESS, SECRET,
                "market=KRW-BTC&side=bid&ord_type=price&price=5000&identifier=0190a5c4-0000-7000-8000-000000000001");
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + expectedToken))
                .andExpect(content().json("""
                        {"market":"KRW-BTC","side":"bid","ord_type":"price","price":"5000","identifier":"0190a5c4-0000-7000-8000-000000000001"}
                        """))
                .andRespond(withSuccess("{\"uuid\":\"u-1\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> res = client.post("/v1/orders", body, AUTH, new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(res).containsEntry("uuid", "u-1");
        server.verify();
    }

    @Test
    void publicGetHasNoAuthorizationHeader() {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();
        q.put("markets", "KRW-BTC,KRW-ETH");
        server.expect(requestTo(BASE_URL + "/v1/ticker?markets=KRW-BTC,KRW-ETH"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> res = client.getPublic("/v1/ticker", q, new ParameterizedTypeReference<>() {});

        assertThat(res).isEmpty();
        server.verify();
    }

    @Test
    void errorNamesMapToBrokerExceptions() {
        assertMapped(HttpStatus.UNAUTHORIZED, "no_authorization_ip", BrokerIpNotAllowedException.class);
        assertMapped(HttpStatus.FORBIDDEN, "out_of_scope", BrokerPermissionDeniedException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "out_of_scope", BrokerPermissionDeniedException.class); // 영역에 따라 401
        assertMapped(HttpStatus.UNAUTHORIZED, "invalid_access_key", BrokerCredentialException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "jwt_verification", BrokerCredentialException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "expired_access_key", BrokerCredentialException.class);
        assertMapped(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", BrokerRateLimitException.class);
        assertMapped(HttpStatus.I_AM_A_TEAPOT, "blocked", BrokerRateLimitException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "nonce_used", UpbitApiException.class); // 우리 쪽 결함 — 일반 실패
    }

    @Test
    void unknownErrorKeepsNameButNotRawMessage() {
        server.expect(requestTo(BASE_URL + "/v1/accounts"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"name\":\"invalid_parameter\",\"message\":\"내부 원문\"}}"));

        assertThatThrownBy(() -> client.get("/v1/accounts", new LinkedHashMap<>(), AUTH, new ParameterizedTypeReference<List<Object>>() {}))
                .isInstanceOfSatisfying(UpbitApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.errorName()).isEqualTo("invalid_parameter");
                    assertThat(e.getMessage()).doesNotContain("내부 원문");
                    assertThat(e.isRejected()).isTrue();
                    assertThat(e.vendorLabel()).isEqualTo("Upbit");
                });
    }

    private void assertMapped(HttpStatus status, String name, Class<? extends RuntimeException> expected) {
        server.reset();
        server.expect(requestTo(BASE_URL + "/v1/accounts"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"name\":\"" + name + "\",\"message\":\"m\"}}"));
        assertThatThrownBy(() -> client.get("/v1/accounts", new LinkedHashMap<>(), AUTH, new ParameterizedTypeReference<List<Object>>() {}))
                .isExactlyInstanceOf(expected);
    }
}
