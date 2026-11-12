package com.stockflow.domain.product;

import com.stockflow.domain.product.event.ProductInfoChangedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductInfoCacheInvalidationListener {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ProductInfoCacheInvalidationListener.class
            );

    private static final String KEY_PREFIX =
            "stockflow:product:info:";

    private final StringRedisTemplate redisTemplate;

    public ProductInfoCacheInvalidationListener(
            StringRedisTemplate redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void onProductInfoChanged(
            ProductInfoChangedEvent event
    ) {
        String key = KEY_PREFIX + event.productId();

        // 정상적으로 삭제되면 기존과 동일하게 동작
        try {
            redisTemplate.delete(key);

            // Redis 연결 오류가 발생하면 오류 로그를 기록
            // 삭제 예외를 호출자에게 전달하지 않으므로
            // 이미 커밋된 상품 수정 요청이
            // 캐시 오류 때문에 실패한 것처럼 보이는 상황을 방지
        } catch (DataAccessException e) {
            // 단순히 예외를 무시하는 것이 아니라
            // 운영 중 캐시 무효화 실패를 추적할 수 있도록 기록
            log.error(
                    "상품 캐시 무효화 실패. productId={}, key={}",
                    event.productId(),
                    key,
                    e
            );
        }
    }
}