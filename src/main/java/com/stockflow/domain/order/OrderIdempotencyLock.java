package com.stockflow.domain.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Advisory Lock 전용 클래스
@Component
public class OrderIdempotencyLock {

    private final JdbcTemplate jdbcTemplate;

    public OrderIdempotencyLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lock(String idempotencyKey) {

        // pg_advisory_xact_lock()는
        // PostgreSQL이 제공하는 트랜잭션 단위 잠금
        // hashtextextended()는 문자열 키를 64비트 잠금 식별자로 변환
        // 서로 다른 문자열이 드물게 동일한 해시를 가질 수 있지만,
        // 그 경우에도 불필요하게 대기할 뿐 주문 데이터의 일치 여부는
        // 기존 로직과 UNIQUE 제약조건으로 검증
        // 트랜잭션 단위 Advisory Lock은 트랜잭션 종료 시 자동 해제됨
        jdbcTemplate.query(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "멱등성 락을 획득하지 못했습니다."
                        );
                    }
                    return null;
                },
                idempotencyKey
        );
    }
}