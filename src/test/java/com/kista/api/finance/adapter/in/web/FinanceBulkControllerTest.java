package com.kista.api.finance.adapter.in.web;

import com.kista.api.admin.application.port.output.AppErrorLogPort;
import com.kista.api.finance.application.service.BulkFinanceRegisterService;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.BulkRegisterPreview;
import com.kista.api.finance.domain.model.BulkRegisterPreview.GroupNode;
import com.kista.api.finance.domain.model.BulkRegisterPreview.Item;
import com.kista.api.finance.domain.model.BulkRegisterPreview.PathSegment;
import com.kista.api.finance.domain.model.Market;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.userToken;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FinanceBulkController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceBulkControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean BulkFinanceRegisterService bulkFinanceRegisterService;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void preview_returnsGroupTreeWithoutIncludedFlag() throws Exception {
        UUID catId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Item item = new Item(itemId, catId, "예금", List.of(new PathSegment(catId, "예금", 0)),
                null, 5_000_000L, AssetClass.CASH, Market.DOMESTIC, null, null, null, null, null);
        BulkRegisterPreview preview = new BulkRegisterPreview(
                List.of(new GroupNode(catId, "예금", 0, List.of(item), List.of())), List.of(), List.of(), List.of());
        when(bulkFinanceRegisterService.preview(USER_ID, YearMonth.of(2026, 7))).thenReturn(preview);

        mockMvc.perform(get("/api/finance/bulk-register/preview").param("sourceMonth", "2026-07")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset[0].name").value("예금"))
                .andExpect(jsonPath("$.asset[0].items[0].id").value(itemId.toString()))
                .andExpect(jsonPath("$.asset[0].items[0].assetClass").value("CASH"))
                .andExpect(jsonPath("$.asset[0].items[0].market").value("DOMESTIC"))
                .andExpect(jsonPath("$.asset[0].items[0].memo").value(nullValue()))
                .andExpect(jsonPath("$.asset[0].items[0].included").doesNotExist())
                .andExpect(jsonPath("$.income").isEmpty());
    }

    @Test
    void preview_invalidSourceMonth_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/bulk-register/preview").param("sourceMonth", "2026-13")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }
}
