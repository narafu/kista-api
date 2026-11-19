package com.kista.api;

import com.kista.api.market.adapter.in.schedule.FearGreedScheduler;
import com.kista.tradingcore.account.adapter.in.schedule.ApiKeyExpiryScheduler;
import com.kista.tradingcore.marketcalendar.adapter.in.schedule.MarketCalendarRefreshScheduler;
import com.kista.tradingcore.trading.adapter.in.schedule.TradingBatchResumer;
import com.kista.api.benchmark.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.api.benchmark.adapter.in.schedule.KbLandPriceIndexScheduler;
import com.kista.api.benchmark.adapter.in.schedule.MarketIndexPriceSyncScheduler;
import com.kista.tradingcore.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.tradingcore.trading.adapter.in.schedule.TradingOpenScheduler;
import com.kista.tradingcore.trading.adapter.in.web.TradingSchedulerInternalController;
import com.kista.api.user.adapter.in.schedule.RefreshTokenCleanupScheduler;
import com.kista.api.web.AdminSchedulerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// web role(kista.role=web) 시 root·매매 엔진 스케줄러 빈과 수동 트리거 컨트롤러(동일 게이트)가
// 컨텍스트에 전혀 없어야 한다 — 합병 뒤 매매 엔진 빈도 같은 컨텍스트에 올라오므로 함께 검증한다. @ConditionalOnWorkerRole이 실제 컨텍스트 로드에서 작동하는지 end-to-end 검증.
@SpringBootTest(properties = "kista.role=web")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class SchedulerDisabledContextTest {

    // FinanceRegistrationReminderScheduler는 package-private이라 클래스 리터럴로 못 씀 → FQCN으로 조회
    private static final String FINANCE_SCHEDULER_FQCN =
            "com.kista.api.finance.adapter.in.schedule.FinanceRegistrationReminderScheduler";
    // 매매 엔진 실패 이벤트 재발행 잡도 package-private
    private static final String TRADING_RESUBMIT_SCHEDULER_FQCN =
            "com.kista.tradingcore.trading.adapter.in.schedule.UserEventResubmitScheduler";

    @Autowired
    private ApplicationContext context;

    @Test
    void 스케쥴러_비활성_시_모든_스케쥴러_빈이_미등록된다() throws ClassNotFoundException {
        Class<?>[] schedulers = {
                TradingOpenScheduler.class, TradingCloseScheduler.class,
                FearGreedScheduler.class, MarketCalendarRefreshScheduler.class,
                KbLandHousingBenchmarkScheduler.class, KbLandPriceIndexScheduler.class,
                MarketIndexPriceSyncScheduler.class, RefreshTokenCleanupScheduler.class,
                Class.forName(FINANCE_SCHEDULER_FQCN), AdminSchedulerController.class,
                ApiKeyExpiryScheduler.class, TradingBatchResumer.class, TradingSchedulerInternalController.class,
                Class.forName(TRADING_RESUBMIT_SCHEDULER_FQCN),
        };
        for (Class<?> type : schedulers) {
            assertThat(context.getBeanNamesForType(type))
                    .as("%s 는 kista.role=web 에서 미등록이어야 한다", type.getSimpleName())
                    .isEmpty();
        }
    }

    // .github/scripts/detect-deploy-scope.sh 가 adapter/in/schedule/* 변경을 scheduler 전용(deploy-api 생략)으로 분류하는 전제 —
    // 게이트 없는 빈이 이 패키지에 생기면 kista-api가 배포 없이 낡은 코드로 남는다. 위 테스트는 열거된 클래스만 보므로 패키지 전체로 검증
    @Test
    void 스케쥴러_비활성_시_schedule_패키지_소속_빈이_하나도_없다() {
        List<String> leaked = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getName().contains(".adapter.in.schedule.");
                })
                .toList();
        assertThat(leaked).as("kista.role=web 인데 등록된 schedule 패키지 빈").isEmpty();
    }
}
