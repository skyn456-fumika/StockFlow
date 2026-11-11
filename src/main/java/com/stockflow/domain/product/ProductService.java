package com.stockflow.domain.product;

import com.stockflow.domain.inventory.Inventory;
import com.stockflow.domain.inventory.InventoryRepository;
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

    public ProductService(
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryHistoryRepository inventoryHistoryRepository
    ) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
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

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "상품을 찾을 수 없습니다."
                ));

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() -> new IllegalStateException(
                        "상품의 재고 정보가 없습니다."
                ));

        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStatus(),
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
}