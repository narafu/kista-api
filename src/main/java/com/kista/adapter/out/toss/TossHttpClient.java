package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
class TossHttpClient {

    private final RestTemplate tossRestTemplate;
    private final TossAuthApi tossAuthApi; // 포트 대신 같은 패키지 구체 클래스 직접 주입
    @Value("${toss.base-url}")
    private final String baseUrl;

    // 계좌 컨텍스트 API용 — X-Tossinvest-Account 헤더 포함 (주문·잔고·매수가능금액)
    public <T> T get(String path, Account account, MultiValueMap<String, String> params, Class<T> responseType) {
        return executeGet(path, account, params, responseType, true);
    }

    // 계좌 헤더 불필요 API용 — 시세 조회·환율 등 (개별 계좌 토큰 사용)
    public <T> T getNoAccountHeader(String path, Account account, MultiValueMap<String, String> params, Class<T> responseType) {
        return executeGet(path, account, params, responseType, false);
    }

    // 공통 API용 — 관리자 토큰 사용, 계좌 컨텍스트 불필요 (시세·환율·캔들·시장정보)
    public <T> T getCommon(String path, MultiValueMap<String, String> params, Class<T> responseType) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithBackoffRetry("관리자", path, tossAuthApi::getAdminToken,
                tossAuthApi::recoverAdminToken,
                token -> {
                    HttpHeaders headers = buildAdminHeaders(token);
                    return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
                });
    }

    public <T> T post(String path, Account account, Object body, Class<T> responseType) {
        return executeWithRetry(account, path, token -> tossRestTemplate.exchange(
                baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, buildHeaders(account, token)), responseType
        ).getBody());
    }

    // ParameterizedTypeReference 오버로드 — 제네릭 래퍼 타입(TossResult<T> 등) 역직렬화용

    // 계좌 컨텍스트 API용 (ParameterizedTypeReference 버전)
    public <T> T get(String path, Account account, MultiValueMap<String, String> params,
                     ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithRetry(account, path, token -> {
            HttpHeaders headers = buildHeaders(account, token);
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
        });
    }

    // 계좌 헤더 불필요 API용 (ParameterizedTypeReference 버전)
    public <T> T getNoAccountHeader(String path, Account account, MultiValueMap<String, String> params,
                                    ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithRetry(account, path, token -> {
            HttpHeaders headers = buildHeadersNoAccount(token);
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
        });
    }

    // 공통 API용 (ParameterizedTypeReference 버전)
    public <T> T getCommon(String path, MultiValueMap<String, String> params,
                           ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithBackoffRetry("관리자", path, tossAuthApi::getAdminToken,
                tossAuthApi::recoverAdminToken,
                token -> {
                    HttpHeaders headers = buildAdminHeaders(token);
                    return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
                });
    }

    // POST 요청 (ParameterizedTypeReference 버전)
    public <T> T post(String path, Account account, Object body, ParameterizedTypeReference<T> typeRef) {
        return executeWithRetry(account, path, token -> tossRestTemplate.exchange(
                baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, buildHeaders(account, token)), typeRef
        ).getBody());
    }

    // DELETE 요청 — 주문 취소 등 (응답 body 없음)
    public void delete(String path, Account account) {
        executeWithRetry(account, path, token -> {
            tossRestTemplate.exchange(
                    baseUrl + path, HttpMethod.DELETE,
                    new HttpEntity<>(buildHeaders(account, token)), Void.class
            );
            return null;
        });
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private <T> T executeGet(String path, Account account, MultiValueMap<String, String> params,
                              Class<T> responseType, boolean withAccountHeader) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithRetry(account, path, token -> {
            HttpHeaders headers = withAccountHeader ? buildHeaders(account, token) : buildHeadersNoAccount(token);
            return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
        });
    }

    // 401 재시도 시 백오프 간격(ms) — 재발급 직후 토큰이 Toss 리소스 서버에 즉시 반영되지 않는 경우 대응
    private static final long RETRY_BACKOFF_MILLIS = 300;
    // 최초 시도 이후 허용하는 최대 401 재시도 횟수
    private static final int MAX_RETRY_ATTEMPTS = 2;

    // 계좌 토큰 재시도 — 공통 헬퍼에 계좌별 토큰 조회/원자적 401 복구만 주입
    private <T> T executeWithRetry(Account account, String path, Function<String, T> call) {
        return executeWithBackoffRetry("계좌", path,
                () -> tossAuthApi.getToken(account.id(), account.appKey(), account.secretKey()),
                token -> tossAuthApi.recoverToken(
                        account.id(), account.appKey(), account.secretKey(), token),
                call);
    }

    // 재시도 간 백오프 — 인터럽트 시 상태만 복원하고 즉시 재시도 진행(대기 없이)
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // 401 → 실패 토큰을 최신/최근 발급 세대와 원자적으로 비교한 뒤 최대 MAX_RETRY_ATTEMPTS회 재시도한다.
    // 재시도 사이 짧은 백오프를 둬 갓 재발급된 토큰의 리소스 서버 반영 지연을 흡수한다.
    // 계좌 토큰(executeWithRetry)·관리자 토큰(getCommon) 양쪽이 공유하는 재시도 골격.
    private <T> T executeWithBackoffRetry(String tokenKind, String path, Supplier<String> tokenFetcher,
                                           Function<String, TossDistributedTokenCoordinator.RecoveredToken> tokenRecoverer,
                                           Function<String, T> call) {
        String token = tokenFetcher.get();
        boolean refreshed = false; // 최초 401에서만 토큰을 갱신하고 이후에는 신규 토큰을 재사용
        for (int attempt = 0; ; attempt++) {
            try {
                return call.apply(token);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 401) {
                    throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
                }
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + e.getMessage(), e);
                }
                // 최초 401은 같은 발급 락의 원자적 복구로 교체/최근 세대를 먼저 확보한 뒤 전파를 기다린다.
                if (!refreshed) {
                    log.warn("Toss 401 — {} 토큰 복구 후 전파 대기 재시도 {}/{}: path={}",
                            tokenKind, attempt + 1, MAX_RETRY_ATTEMPTS, path);
                    TossDistributedTokenCoordinator.RecoveredToken recovered = tokenRecoverer.apply(token);
                    token = recovered.accessToken();
                    // 이미 다른 인스턴스가 저장해 둔 canonical 토큰을 Redis 읽기만으로 재사용한 경우
                    // (freshlyIssued=false)에는 전파 지연 위험이 없으므로 백오프 대기를 생략한다.
                    if (recovered.freshlyIssued()) {
                        sleepBackoff(attempt);
                    }
                    refreshed = true;
                } else {
                    log.warn("Toss 401 — {} 신규 토큰 전파 대기 재시도 {}/{}: path={}",
                            tokenKind, attempt + 1, MAX_RETRY_ATTEMPTS, path);
                    sleepBackoff(attempt);
                }
            } catch (RestClientException e) {
                throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
            }
        }
    }

    // 계좌 컨텍스트 헤더 (X-Tossinvest-Account 포함) — Account.brokerAccountCode에 accountSeq가 저장됨
    private HttpHeaders buildHeaders(Account account, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("X-Tossinvest-Account", account.brokerAccountCode());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // 계좌 헤더 미포함 — 시세 조회·환율 등 계좌 컨텍스트 불필요 API용
    private HttpHeaders buildHeadersNoAccount(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // 관리자 토큰 헤더 — X-Tossinvest-Account 없이 Bearer 토큰만 (매 시도의 토큰을 인자로 받는다)
    private HttpHeaders buildAdminHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
