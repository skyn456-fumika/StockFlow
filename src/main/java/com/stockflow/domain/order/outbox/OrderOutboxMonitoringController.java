package com.stockflow.domain.order.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/api/admin/outbox")
@ConditionalOnProperty(
        prefix = "stockflow.monitoring",
        name = "enabled",
        havingValue = "true"
)
public class OrderOutboxMonitoringController {

    private final OrderOutboxMonitoringService monitoringService;
    private final byte[] adminToken;

    public OrderOutboxMonitoringController(
            OrderOutboxMonitoringService monitoringService,
            @Value("${stockflow.monitoring.admin-token:}") String adminToken
    ) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new IllegalStateException(
                    "모니터링 API 활성화 시 관리자 토큰이 필요합니다."
            );
        }

        this.monitoringService = monitoringService;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    // Outbox 상태별 건수
    @GetMapping("/summary")
    public OutboxStatusSummary getSummary(
            @RequestHeader(
                    value = "X-Admin-Token",
                    required = false
            ) String token
    ) {
        validateAdminToken(token);

        return monitoringService.getSummary();
    }

    // 재시도·장기 대기 이벤트 목록
    @GetMapping("/attention")
    public List<OutboxAttentionItem> getAttentionRequired(
            @RequestHeader(
                    value = "X-Admin-Token",
                    required = false
            ) String token
    ) {
        validateAdminToken(token);

        return monitoringService.getAttentionRequired();
    }

    private void validateAdminToken(String token) {

        if (token == null || !MessageDigest.isEqual(
                adminToken,
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "관리자 인증에 실패했습니다."
            );
        }
    }

    // 관리자 장애 감지 API
    @GetMapping("/alerts")
    public OutboxAlertSummary getAlerts(
            @RequestHeader(
                    value = "X-Admin-Token",
                    required = false
            ) String token
    ) {
        validateAdminToken(token);

        return monitoringService.getAlertSummary();
    }
}