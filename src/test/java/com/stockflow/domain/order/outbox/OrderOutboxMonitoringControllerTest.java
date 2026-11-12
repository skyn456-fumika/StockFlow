package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderOutboxMonitoringControllerTest {

    private MockMvc mockMvc;
    private OrderOutboxMonitoringService monitoringService;

    @BeforeEach
    void setUp() {
        monitoringService = mock(OrderOutboxMonitoringService.class);

        OrderOutboxMonitoringController controller =
                new OrderOutboxMonitoringController(
                        monitoringService,
                        "test-admin-token"
                );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    @Test
    void 올바른_관리자_토큰이면_Outbox_집계정보를_반환한다()
            throws Exception {

        when(monitoringService.getSummary())
                .thenReturn(new OutboxStatusSummary(
                        10, 7, 3, 30
                ));

        mockMvc.perform(
                        get("/api/admin/outbox/summary")
                                .header(
                                        "X-Admin-Token",
                                        "test-admin-token"
                                )
                                .accept(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(10))
                .andExpect(jsonPath("$.ready").value(7))
                .andExpect(jsonPath("$.delayed").value(3))
                .andExpect(jsonPath("$.published").value(30));

        verify(monitoringService).getSummary();
    }

    @Test
    void 관리자_토큰이_없으면_401을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/admin/outbox/summary")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoringService);
    }

    @Test
    void 관리자_토큰이_틀리면_401을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/admin/outbox/summary")
                                .header(
                                        "X-Admin-Token",
                                        "wrong-token"
                                )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoringService);
    }

    @Test
    void 관리자_토큰이_설정되지_않으면_컨트롤러를_생성할_수_없다() {

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new OrderOutboxMonitoringController(
                        monitoringService,
                        ""
                )
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 올바른_관리자_토큰이면_관리대상_이벤트_목록을_반환한다()
            throws Exception {

        UUID eventId = UUID.randomUUID();

        LocalDateTime createdAt =
                LocalDateTime.of(2026, 11, 12, 10, 0);

        LocalDateTime nextRetryAt =
                LocalDateTime.of(2026, 11, 12, 10, 5);

        OutboxAttentionItem item = new OutboxAttentionItem(
                1L,
                eventId,
                100L,
                "ORDER_CREATED",
                2,
                createdAt,
                nextRetryAt
        );

        when(monitoringService.getAttentionRequired())
                .thenReturn(List.of(item));

        mockMvc.perform(
                        get("/api/admin/outbox/attention")
                                .header(
                                        "X-Admin-Token",
                                        "test-admin-token"
                                )
                                .accept(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].eventId")
                        .value(eventId.toString()))
                .andExpect(jsonPath("$[0].orderId").value(100))
                .andExpect(jsonPath("$[0].eventType")
                        .value("ORDER_CREATED"))
                .andExpect(jsonPath("$[0].retryCount").value(2))
                .andExpect(jsonPath("$[0].createdAt")
                        .value("2026-11-12T10:00:00"))
                .andExpect(jsonPath("$[0].nextRetryAt")
                        .value("2026-11-12T10:05:00"));

        verify(monitoringService).getAttentionRequired();
    }

    @Test
    void 관리자_토큰이_없으면_관리대상_목록_조회도_401을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/admin/outbox/attention")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoringService);
    }

    @Test
    void 잘못된_관리자_토큰으로_관리대상_목록을_조회하면_401을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/admin/outbox/attention")
                                .header(
                                        "X-Admin-Token",
                                        "wrong-token"
                                )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoringService);
    }

    @Test
    void 관리자_토큰이_올바르면_장애_감지_결과를_반환한다()
            throws Exception {

        when(monitoringService.getAlertSummary())
                .thenReturn(new OutboxAlertSummary(
                        3, 2, true
                ));

        mockMvc.perform(
                        get("/api/admin/outbox/alerts")
                                .header(
                                        "X-Admin-Token",
                                        "test-admin-token"
                                )
                                .accept(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stalePending").value(3))
                .andExpect(jsonPath("$.highRetry").value(2))
                .andExpect(jsonPath("$.alert").value(true));

        verify(monitoringService).getAlertSummary();
    }

    @Test
    void 장애_조건이_없으면_alert_false를_반환한다()
            throws Exception {

        when(monitoringService.getAlertSummary())
                .thenReturn(new OutboxAlertSummary(
                        0, 0, false
                ));

        mockMvc.perform(
                        get("/api/admin/outbox/alerts")
                                .header(
                                        "X-Admin-Token",
                                        "test-admin-token"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stalePending").value(0))
                .andExpect(jsonPath("$.highRetry").value(0))
                .andExpect(jsonPath("$.alert").value(false));
    }

    @Test
    void 관리자_토큰이_없으면_장애_조회도_401을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/admin/outbox/alerts")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoringService);
    }

    @Test
    void 잘못된_관리자_토큰으로_장애_조회시_401을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/admin/outbox/alerts")
                                .header(
                                        "X-Admin-Token",
                                        "wrong-token"
                                )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(monitoringService);
    }
}