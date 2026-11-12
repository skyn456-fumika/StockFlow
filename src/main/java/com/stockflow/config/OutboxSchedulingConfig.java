package com.stockflow.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "stockflow.outbox",
        name = "scheduling-enabled",
        havingValue = "true"            // 해당 속성이 true일 때만 스케줄링을 활성화
)
public class OutboxSchedulingConfig {
}