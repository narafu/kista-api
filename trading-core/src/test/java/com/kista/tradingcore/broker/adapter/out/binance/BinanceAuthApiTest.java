package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerWithdrawalEnabledException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceAuthApiTest {

    private static final String BASE = "https://api.binance.com";
    private MockRestServiceServer server;
    private BinanceAuthApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        api = new BinanceAuthApi(new BinanceHttpClient(builder.build(), JsonMapper.builder().build(), BASE,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)));
    }

    private void respond(boolean ip, boolean read, boolean trade, boolean withdraw) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/sapi/v1/account/apiRestrictions?")))
                .andRespond(withSuccess("{\"ipRestrict\":%s,\"enableReading\":%s,\"enableSpotAndMarginTrading\":%s,\"enableWithdrawals\":%s,\"createTime\":1}"
                        .formatted(ip, read, trade, withdraw), MediaType.APPLICATION_JSON));
    }

    @Test
    void validKeyReturnsNullExpiry() {
        respond(true, true, true, false);
        assertThat(api.verifyAccount("ak", "sk", "ignored")).isEqualTo(new BrokerAccountVerification(null, null));
        server.verify();
    }

    @Test
    void withdrawalEnabledIsRejected() {
        respond(true, true, true, true);
        assertThatThrownBy(() -> api.verifyAccount("ak", "sk", null)).isExactlyInstanceOf(BrokerWithdrawalEnabledException.class);
    }

    @Test
    void noIpRestrictionIsRejected() {
        respond(false, true, true, false);
        assertThatThrownBy(() -> api.verifyAccount("ak", "sk", null)).isExactlyInstanceOf(BrokerIpNotAllowedException.class);
    }

    @Test
    void tradingDisabledIsRejected() {
        respond(true, true, false, false);
        assertThatThrownBy(() -> api.verifyAccount("ak", "sk", null)).isExactlyInstanceOf(BrokerPermissionDeniedException.class);
    }

    @Test
    void readingDisabledIsRejected() {
        respond(true, false, true, false);
        assertThatThrownBy(() -> api.verifyAccount("ak", "sk", null)).isExactlyInstanceOf(BrokerPermissionDeniedException.class);
    }

    @Test
    void verifyCredentialsAppliesSameRestrictionChecks() {
        respond(true, true, true, true);
        assertThatThrownBy(() -> api.verifyCredentials("ak", "sk")).isExactlyInstanceOf(BrokerWithdrawalEnabledException.class);
    }

    @Test
    void invalidKeyIsCredentialFailure() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/sapi/v1/account/apiRestrictions?")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"code\":-2015,\"msg\":\"x\"}"));
        assertThatThrownBy(() -> api.verifyCredentials("ak", "sk")).isExactlyInstanceOf(BrokerCredentialException.class);
    }

    @Test
    void supportsBinance() {
        assertThat(api.supports()).isEqualTo(Broker.BINANCE);
    }
}
