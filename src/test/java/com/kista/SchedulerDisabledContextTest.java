package com.kista;

import com.kista.market.adapter.in.schedule.FearGreedScheduler;
import com.kista.market.adapter.in.schedule.MarketCalendarRefreshScheduler;
import com.kista.stats.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.stats.adapter.in.schedule.KbLandPriceIndexScheduler;
import com.kista.stats.adapter.in.schedule.MarketIndexPriceSyncScheduler;
import com.kista.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.trading.adapter.in.schedule.TradingOpenScheduler;
import com.kista.user.adapter.in.schedule.RefreshTokenCleanupScheduler;
import com.kista.web.AdminSchedulerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// API role(scheduler.enabled=false) 시 9개 @Scheduled 빈 + AdminSchedulerController(동일 게이트)가
// 컨텍스트에 전혀 없어야 한다. @ConditionalOnProperty가 실제 컨텍스트 로드에서 작동하는지 end-to-end 검증.
@SpringBootTest(properties = "scheduler.enabled=false")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class SchedulerDisabledContextTest {

    // FinanceRegistrationReminderScheduler는 package-private이라 클래스 리터럴로 못 씀 → FQCN으로 조회
    private static final String FINANCE_SCHEDULER_FQCN =
            "com.kista.finance.adapter.in.schedule.FinanceRegistrationReminderScheduler";

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
        };
        for (Class<?> type : schedulers) {
            assertThat(context.getBeanNamesForType(type))
                    .as("%s 는 scheduler.enabled=false 에서 미등록이어야 한다", type.getSimpleName())
                    .isEmpty();
        }
    }
}
