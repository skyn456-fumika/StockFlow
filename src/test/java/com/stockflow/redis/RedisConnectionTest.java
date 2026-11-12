package com.stockflow.redis;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.springframework.data.redis.core.StringRedisTemplate;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
// Redis 통합 테스트
class RedisConnectionTest {

    // 별도의 PostgreSQL과 Redis 컨테이너를 사용
    // 테스트 실행 시 로컬 Redis의 6380 포트에 직접 의존하지 않음
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    // Spring Boot가 Testcontainers의 Redis 연결 정보를 자동으로 사용하도록 지정하는 부분
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    // Redis에 문자열 기반 데이터를 저장하고 조회하는 Spring Data Redis의 클래스
    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void Redis에_문자열을_저장하고_조회한다() {

        String key = "stockflow:test:" + UUID.randomUUID();

        try {
            // 저장
            redisTemplate.opsForValue().set(
                    key,
                    "hello-stockflow"
            );

            // 조회
            String value = redisTemplate.opsForValue().get(key);

            assertThat(value).isEqualTo("hello-stockflow");

        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void Redis_데이터에_TTL을_설정할_수_있다() {

        String key = "stockflow:ttl:" + UUID.randomUUID();

        try {
            // 해당 데이터가 최대 5분 동안 유지되도록 설정
            // 상품 캐시를 구현할 때 오래된 데이터가 무기한 남지 않도록 하기 위해 활용할 예정
            redisTemplate.opsForValue().set(
                    key,
                    "cached-value",
                    Duration.ofMinutes(5)
            );

            String value = redisTemplate.opsForValue().get(key);

            Long ttl = redisTemplate.getExpire(key);

            assertThat(value).isEqualTo("cached-value");

            assertThat(ttl)
                    .isNotNull()
                    .isPositive()
                    .isLessThanOrEqualTo(300L);

        } finally {
            redisTemplate.delete(key);
        }
    }
}