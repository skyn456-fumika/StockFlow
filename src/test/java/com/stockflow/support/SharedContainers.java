package com.stockflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

// 공통 컨테이너 클래스
// JVM 안에서 한 번 초기화되므로,
// 상속받는 테스트 클래스들이 동일한 컨테이너 인스턴스를 사용할 수 있음
public abstract class SharedContainers {

    @ServiceConnection
    protected static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @ServiceConnection
    protected static final RabbitMQContainer rabbitmq =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    static {
        postgres.start();
        redis.start();
        rabbitmq.start();
    }
}