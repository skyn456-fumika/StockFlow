package com.stockflow.domain.product;

import com.stockflow.domain.product.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductCreateResponse> createProduct(
            @Valid @RequestBody ProductCreateRequest request
    ) {
        Long productId = productService.registerProduct(
                request.name(),
                request.price(),
                request.initialQuantity()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                // 생성된 상품의 향후 조회 주소를 나타냄
                .location(URI.create("/api/products/" + productId))
                .body(new ProductCreateResponse(productId));
    }

    // 상품 ID를 받아 Service에 전달하고, 상품 정보와 재고 수량을 JSON으로 반환
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(
            @PathVariable Long productId
    ) {
        ProductResponse response = productService.getProduct(productId);

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<ProductPageResponse> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(
                productService.getProducts(page, size)
        );
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Void> updateProduct(
            @PathVariable Long productId,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        productService.updateProductInfo(
                productId,
                request.name(),
                request.price()
        );

        return ResponseEntity.noContent().build();
    }
}