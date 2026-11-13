package com.stockflow.domain.product;

import com.stockflow.domain.product.dto.ProductInfoResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Service
public class ProductInfoCacheService {

    private static final Logger log =
            LoggerFactory.getLogger(ProductInfoCacheService.class);

    private static final String KEY_PREFIX =
            "stockflow:product:info:";

    private static final Duration TTL = Duration.ofMinutes(10);

    // 기존 환경에서는 지금처럼 Redis 캐시를 사용
    // true	: Redis 조회 → MISS 시 DB 조회 후 캐시 저장
    // false : Redis를 거치지 않고 DB 조회
    // 설정 없음 : 기본값 true
    @Value("${stockflow.cache.product.enabled:true}")
    private boolean cacheEnabled = true;

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper objectMapper;

    public ProductInfoCacheService(
            ProductRepository productRepository,
            StringRedisTemplate redisTemplate,
            JsonMapper objectMapper
    ) {
        this.productRepository = productRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public ProductInfoResponse getProductInfo(Long productId) {

        // 캐시 OFF: Redis에 접근하지 않고 PostgreSQL 조회
        if (!cacheEnabled) {
            return loadFromDb(productId);
        }

        String key = KEY_PREFIX + productId;

        // 1. Redis 캐시 조회
        try {
            String cached = redisTemplate.opsForValue().get(key);

            if (cached != null) {
                try {
                    return objectMapper.readValue(
                            cached,
                            ProductInfoResponse.class
                    );
                } catch (JacksonException e) {
                    log.warn("상품 캐시 역직렬화 실패. productId={}", productId);

                    try {
                        redisTemplate.delete(key);
                    } catch (DataAccessException deleteError) {
                        log.warn("손상된 상품 캐시 삭제 실패. productId={}",
                                productId, deleteError);
                    }
                }
            }

        } catch (DataAccessException e) {
            log.warn("Redis 조회 실패. DB 조회로 전환. productId={}",
                    productId, e);
        }

        // 2. PostgreSQL 조회
        ProductInfoResponse response = loadFromDb(productId);

        // 3. Redis 캐시 저장
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, TTL);

        } catch (DataAccessException e) {
            log.warn("Redis 저장 실패. DB 조회 결과를 반환. productId={}",
                    productId, e);
        }

        return response;
    }

    // PostgreSQL 조회 공통 메서드
    private ProductInfoResponse loadFromDb(Long productId) {

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "상품을 찾을 수 없습니다."
                ));

        return ProductInfoResponse.from(product);
    }

}