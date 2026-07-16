package com.kista.adapter.out.kakao;

import com.kista.domain.port.out.KakaoOAuthPort.KakaoUserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoOAuthAdapterTest {

    @Test
    void 인가_코드로_토큰을_교환하고_사용자_정보를_조회한다() {
        RestTemplate restTemplate = new KakaoConfig().kakaoRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        KakaoProperties properties = new KakaoProperties("test-client-id", "test-secret");
        KakaoOAuthAdapter adapter = new KakaoOAuthAdapter(restTemplate, properties);

        // 토큰 교환 요청 — grant_type/client_id/redirect_uri/code/client_secret 폼 파라미터 검증
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/x-www-form-urlencoded"))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("grant_type=authorization_code");
                    assertThat(body).contains("client_id=test-client-id");
                    assertThat(body).contains("code=auth-code");
                    assertThat(body).contains("redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fcallback");
                    assertThat(body).contains("client_secret=test-secret");
                })
                .andRespond(withSuccess("""
                        {
                          "access_token": "kakao-access-token",
                          "token_type": "bearer",
                          "refresh_token": "kakao-refresh-token",
                          "expires_in": 21599,
                          "scope": "account_email profile_nickname",
                          "refresh_token_expires_in": 5184000
                        }
                        """, MediaType.APPLICATION_JSON));

        // 사용자 정보 조회 요청 — Bearer 토큰 헤더 검증 + properties.nickname 파싱 확인
        // MockRestServiceServer는 실제 요청 발생 이후 expectation 추가를 허용하지 않으므로 미리 등록
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer kakao-access-token"))
                .andRespond(withSuccess("""
                        {
                          "id": 123456789,
                          "connected_at": "2024-01-01T00:00:00Z",
                          "properties": {
                            "nickname": "테스트유저"
                          },
                          "kakao_account": {
                            "profile": {
                              "nickname": "테스트유저"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        String accessToken = adapter.exchangeCodeForToken("auth-code", "http://localhost:3000/callback");

        assertThat(accessToken).isEqualTo("kakao-access-token");

        KakaoUserInfo userInfo = adapter.getUserInfo(accessToken);

        assertThat(userInfo.kakaoId()).isEqualTo("123456789");
        assertThat(userInfo.nickname()).isEqualTo("테스트유저");
        server.verify();
    }

    @Test
    void 토큰_교환_시_카카오가_4xx를_응답하면_HttpClientErrorException이_전파된다() {
        RestTemplate restTemplate = new KakaoConfig().kakaoRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        KakaoProperties properties = new KakaoProperties("test-client-id", "test-secret");
        KakaoOAuthAdapter adapter = new KakaoOAuthAdapter(restTemplate, properties);

        // 만료·재사용된 인가 코드 — 카카오는 400 Bad Request로 응답
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {"error":"invalid_grant","error_description":"authorization code not found"}
                        """));

        // RestTemplate 기본 오류 핸들러가 예외를 던지므로 어댑터의 !is2xxSuccessful() 분기는 도달하지 않음
        assertThatThrownBy(() -> adapter.exchangeCodeForToken("expired-code", "http://localhost:3000/callback"))
                .isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void 사용자_정보_조회_시_카카오가_5xx를_응답하면_HttpServerErrorException이_전파된다() {
        RestTemplate restTemplate = new KakaoConfig().kakaoRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        KakaoProperties properties = new KakaoProperties("test-client-id", "test-secret");
        KakaoOAuthAdapter adapter = new KakaoOAuthAdapter(restTemplate, properties);

        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getUserInfo("kakao-access-token"))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }
}
