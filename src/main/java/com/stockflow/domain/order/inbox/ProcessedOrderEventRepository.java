package com.stockflow.domain.order.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedOrderEventRepository
        extends JpaRepository<ProcessedOrderEvent, UUID> {
}