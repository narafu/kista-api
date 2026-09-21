package com.kista.architecture;

import com.kista.admin.domain.model.*;
import com.kista.market.domain.model.MarketSession;
import com.kista.market.domain.model.TossDailyCandle;
import com.kista.matching.adapter.in.web.StrategyCapabilityResponse;
import com.kista.marketcalendar.domain.model.MarketSessionSnapshot;
import com.kista.notify.domain.model.TradeEventView;
import com.kista.privacy.domain.model.*;
import com.kista.stats.application.port.output.InvestmentPointsPort;
import com.kista.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.domain.model.*;
import com.kista.trading.stats.adapter.in.web.dto.InvestmentPointsResponse;
import com.kista.web.dto.StrategyCapability;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 루트(:api) ↔ :trading-core own-type 복제쌍 JSON 계약 검증 — constraints.md "모듈 경계 own-type" (a) 순환 불가피 쌍만 대상.
// 양쪽이 서로의 클래스를 import할 수 없어 필드 shape을 손으로 맞추는데, Jackson은 미지의 필드를 조용히 버리므로
// 한쪽만 바뀌면 컴파일·런타임 모두 무증상이다 (루트 테스트 소스만 :trading-core를 참조할 수 있어 이 위치에 둔다).
// (b) 외부 계약 분리 쌍(TossCandleResponse·CycleHistory*Response)은 독립 진화가 의도라 일부러 제외했다.
// 규칙: 읽는 쪽(reader) 컴포넌트는 쓰는 쪽(writer)에 같은 이름·타입으로 존재해야 한다 — writer가 필드를 더 갖는 건
// 의도된 narrowing(AdminAccountView 등). enum은 writer 상수를 reader가 전부 받아야 하고, exact 쌍은 양방향으로 검사한다.
class OwnTypeContractTest {

    private record Pair(Class<?> reader, Class<?> writer, boolean exact) {}

    private static Arguments pair(Class<?> reader, Class<?> writer) {
        return arg(reader, writer, false);
    }

    private static Arguments exact(Class<?> reader, Class<?> writer) {
        return arg(reader, writer, true);
    }

    private static Arguments arg(Class<?> reader, Class<?> writer, boolean exact) {
        return Arguments.of(Named.of(reader.getName() + " ← " + writer.getName(), new Pair(reader, writer, exact)));
    }

    // 패키지 private 중첩 record(컨트롤러·어댑터 내부) — getRecordComponents()는 접근 제한과 무관하다
    private static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Arguments> pairs() {
        return Stream.of(
                // Redis Pub/Sub — 발행(trading-core)·구독(root) byte-identical
                exact(TradeEventView.class, com.kista.trading.notify.domain.model.TradeEventView.class),
                // enum — 양방향 값 집합 동일
                exact(BenchmarkGranularity.class, com.kista.trading.stats.domain.model.BenchmarkGranularity.class),
                exact(MarketSession.class, MarketSessionSnapshot.MarketSession.class),
                // 조회 응답 — trading-core(writer) → root(reader)
                pair(cls("com.kista.market.adapter.out.internal.MarketCalendarQueryHttpAdapter$SessionResponse"),
                        cls("com.kista.marketcalendar.adapter.in.web.MarketCalendarInternalController$SessionResponse")),
                pair(TossDailyCandle.class, cls("com.kista.broker.adapter.in.web.CandleInternalController$CandleResponse")),
                pair(InvestmentPointsPort.Result.class, InvestmentPointsResponse.class),
                pair(StrategyCapability.class, StrategyCapabilityResponse.class),
                pair(AdminAccountView.class, cls("com.kista.account.adapter.in.web.AccountInternalController$AccountInternalResponse")),
                pair(AdminOrderView.class, Order.class),
                pair(AdminStrategyView.class, Strategy.class),
                pair(AdminStrategySummary.class, StrategySummary.class),
                pair(AdminReorderTimingAvailability.class, DstInfo.ReorderTimingAvailability.class),
                pair(AdminReorderResult.class, ReorderResult.class),
                pair(AdminTradeCorrectionResult.class, ManualTradeCorrectionResult.class),
                pair(AdminPrivacyTradeBaseView.class, PrivacyTradeBaseView.class),
                // 요청 바디 — admin(writer) → trading-core(reader)
                pair(ReorderCommand.class, AdminReorderCommand.class),
                pair(ManualTradeCorrectionCommand.class, AdminManualTradeCorrectionCommand.class),
                pair(FidaOrderCommand.class, AdminFidaOrderCommand.class),
                pair(PrivacyBaseUpdateCommand.class, AdminPrivacyBaseUpdateCommand.class),
                pair(PrivacyOrderUpdateCommand.class, AdminPrivacyOrderUpdateCommand.class));
    }

    @ParameterizedTest
    @MethodSource("pairs")
    void reader_components_must_exist_in_writer(Pair p) {
        List<String> problems = new ArrayList<>();
        check(p.reader(), p.writer(), p.reader().getSimpleName(), problems);
        if (p.exact()) {
            check(p.writer(), p.reader(), p.writer().getSimpleName(), problems);
        }
        assertThat(problems).isEmpty();
    }

    private static void check(Type reader, Type writer, String path, List<String> problems) {
        if (reader instanceof ParameterizedType r && writer instanceof ParameterizedType w) { // List<X> 등 — 원소 타입 비교
            check(r.getActualTypeArguments()[0], w.getActualTypeArguments()[0], path + "[]", problems);
        } else if (reader.equals(writer)) {
            return; // 같은 Class(JDK·sharedkernel) — 더 볼 것 없음
        } else if (reader instanceof Class<?> r && writer instanceof Class<?> w && r.isEnum() && w.isEnum()) {
            List<String> readable = Arrays.stream(r.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList();
            Arrays.stream(w.getEnumConstants()).map(e -> ((Enum<?>) e).name())
                    .filter(n -> !readable.contains(n))
                    .forEach(n -> problems.add(path + ": writer enum 상수 " + n + " 을 reader가 받지 못함"));
        } else if (reader instanceof Class<?> r && writer instanceof Class<?> w && r.isRecord() && w.isRecord()) {
            for (RecordComponent rc : r.getRecordComponents()) {
                RecordComponent wc = Arrays.stream(w.getRecordComponents())
                        .filter(c -> c.getName().equals(rc.getName())).findFirst().orElse(null);
                if (wc == null) {
                    problems.add(path + "." + rc.getName() + ": writer에 없음");
                } else {
                    check(rc.getGenericType(), wc.getGenericType(), path + "." + rc.getName(), problems);
                }
            }
        } else {
            problems.add(path + ": 타입 불일치 reader=" + reader.getTypeName() + " writer=" + writer.getTypeName());
        }
    }
}
