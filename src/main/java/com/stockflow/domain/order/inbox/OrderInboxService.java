package com.stockflow.domain.order.inbox;

import com.stockflow.domain.order.outbox.OrderOutboxMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OrderInboxService {

    private static final Logger log =
            LoggerFactory.getLogger(OrderInboxService.class);

    private final JdbcTemplate jdbcTemplate;

    public OrderInboxService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean process(OrderOutboxMessage event) {

        if (event == null
                || event.eventId() == null
                || event.orderId() == null
                || event.eventType() == null) {
            throw new IllegalArgumentException(
                    "올바른 주문 이벤트가 필요합니다."
            );
        }

        if (!event.eventType().equals("ORDER_CREATED")
                && !event.eventType().equals("ORDER_CANCELLED")) {
            throw new IllegalArgumentException(
                    "지원하지 않는 주문 이벤트입니다."
            );
        }

        // DB의 기본키 제약조건으로 중복을 판별(ON CONFLICT DO NOTHING)
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO processed_order_events
                    (event_id, order_id, event_type, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                event.orderId(),
                event.eventType(),
                LocalDateTime.now()
        );

        if (inserted == 0) {
            log.info(
                    "이미 처리된 주문 이벤트. eventId={}",
                    event.eventId()
            );
            return false;
        }

        log.info(
                "주문 이벤트 처리 완료. eventId={}, orderId={}, type={}",
                event.eventId(),
                event.orderId(),
                event.eventType()
        );

        return true;
    }
}