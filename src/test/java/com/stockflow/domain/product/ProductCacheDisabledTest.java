
package com.stockflow.domain.product;

import com.stockflow.domain.product.dto.ProductInfoResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
// 캐시 OFF 동작 테스트
class ProductCacheDisabledTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private JsonMapper objectMapper;

    @InjectMocks
    private ProductInfoCacheService cacheService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                cacheService,
                "cacheEnabled",
                false
        );
    }

    @Test
    void 캐시가_비활성화되면_Redis를_조회하지_않는다() {
        // given
        Long productId = 999L;

        when(productRepository.findById(productId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(
                () -> cacheService.getProductInfo(productId)
        )
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException ex =
                            (ResponseStatusException) exception;

                    org.assertj.core.api.Assertions.assertThat(
                            ex.getStatusCode()
                    ).isEqualTo(HttpStatus.NOT_FOUND);
                });

        // DB 조회는 실행되어야 한다.
        verify(productRepository).findById(productId);

        // Redis 조회·저장은 발생하면 안 된다.
        verifyNoInteractions(redisTemplate);
    }
}
