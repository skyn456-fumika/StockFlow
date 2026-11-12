package com.stockflow.domain.product;

import com.stockflow.domain.inventory.Inventory;
import com.stockflow.domain.inventory.InventoryRepository;
import com.stockflow.domain.inventory.exception.InventoryNotFoundException;
import com.stockflow.domain.product.dto.ProductInfoResponse;
import com.stockflow.domain.product.event.ProductInfoChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import com.stockflow.domain.product.dto.ProductResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stockflow.domain.product.dto.ProductPageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.stockflow.domain.inventory.InventoryChangeType;
import com.stockflow.domain.inventory.InventoryHistory;
import com.stockflow.domain.inventory.InventoryHistoryRepository;

import com.stockflow.global.exception.InvalidPaginationException;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository inventoryHistoryRepository;
    private final ProductInfoCacheService productInfoCacheService;
    private final ApplicationEventPublisher eventPublisher;

    public ProductService(
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryHistoryRepository inventoryHistoryRepository,
            ProductInfoCacheService productInfoCacheService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.productInfoCacheService = productInfoCacheService;
        this.eventPublisher = eventPublisher;
    }

    // 상품 저장에는 성공했는데 재고 저장 중 예외가 발생한다면,
    // 두 작업 모두 롤백되어 상품만 남는 문제를 방지
    @Transactional
    public Long registerProduct(
            String name,
            BigDecimal price,
            int initialQuantity
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "상품명은 필수입니다."
            );
        }

        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException(
                    "가격은 0 이상이어야 합니다."
            );
        }

        if (initialQuantity < 0) {
            throw new IllegalArgumentException(
                    "초기 재고는 0 이상이어야 합니다."
            );
        }

        Product product = productRepository.save(
                new Product(name, price)
        );

        Inventory inventory = inventoryRepository.save(
                new Inventory(product, initialQuantity)
        );

        // 재고 변경 이력 자동 기록
        inventoryHistoryRepository.save(
                new InventoryHistory(
                        inventory,
                        InventoryChangeType.INITIAL,
                        initialQuantity,
                        initialQuantity
                )
        );

        return product.getId();
    }

    // 데이터를 변경하지 않는 조회 작업에 사용하는 설정
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {

        // 상품 기본 정보: Redis 캐시 사용
        ProductInfoResponse info =
                productInfoCacheService.getProductInfo(productId);

        // 재고: PostgreSQL에서 항상 조회
        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() ->
                        new InventoryNotFoundException(productId)
                );

        return new ProductResponse(
                info.productId(),
                info.name(),
                info.price(),
                info.status(),
                inventory.getQuantity()
        );
    }

    // 목록 조회 메서드
    @Transactional(readOnly = true)
    public ProductPageResponse getProducts(int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidPaginationException();
        }

        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "id")
        );

        return ProductPageResponse.from(
                inventoryRepository.findProductSummaries(pageable)
        );
    }

    @Transactional
    public void updateProductInfo(
            Long productId,
            String name,
            BigDecimal price
    ) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "상품을 찾을 수 없습니다."
                ));

        product.updateInfo(name, price);

        // publishEvent()를 호출했다고 해서 Redis 캐시가 즉시 삭제되는 것은 아님
        // 리스너가 트랜잭션 커밋을 기다리도록 만듬
        eventPublisher.publishEvent(
                new ProductInfoChangedEvent(productId)
        );
    }
}