package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.ProductRequest;
import com.antheagao.ecommerce_api.dto.ProductResponse;
import com.antheagao.ecommerce_api.entity.Category;
import com.antheagao.ecommerce_api.entity.Product;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.CategoryRepository;
import com.antheagao.ecommerce_api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void findById_whenExists_returnsResponse() {
        Product product = Product.builder().id(1L).name("Widget").price(new BigDecimal("9.99")).stockQuantity(5).build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.findById(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Widget");
    }

    @Test
    void findById_whenNotExists_throwsResourceNotFoundException() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    void create_whenValid_savesAndReturns() {
        ProductRequest req = new ProductRequest();
        req.setName("Widget");
        req.setPrice(new BigDecimal("9.99"));
        req.setSku("SKU-1");
        req.setStockQuantity(10);
        when(productRepository.existsBySku("SKU-1")).thenReturn(false);
        Product saved = Product.builder().id(1L).name("Widget").price(new BigDecimal("9.99")).sku("SKU-1").stockQuantity(10).build();
        when(productRepository.save(any(Product.class))).thenReturn(saved);

        ProductResponse response = productService.create(req);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getSku()).isEqualTo("SKU-1");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void create_whenDuplicateSku_throwsConflictException() {
        ProductRequest req = new ProductRequest();
        req.setName("Widget");
        req.setPrice(new BigDecimal("9.99"));
        req.setSku("SKU-1");
        when(productRepository.existsBySku("SKU-1")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Product with SKU already exists: SKU-1");
        verify(productRepository, never()).save(any());
    }

    @Test
    void create_whenStockQuantityNull_defaultsToZero() {
        ProductRequest req = new ProductRequest();
        req.setName("Widget");
        req.setPrice(new BigDecimal("9.99"));
        req.setStockQuantity(null);
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        when(productRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        productService.create(req);

        assertThat(captor.getValue().getStockQuantity()).isZero();
    }

    @Test
    void update_whenPartialRequest_onlyChangesNonNullFields() {
        Product product = Product.builder().id(1L).name("Old Name").description("Old Desc")
                .price(new BigDecimal("5.00")).sku("SKU-OLD").stockQuantity(3).build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductRequest req = new ProductRequest();
        req.setPrice(new BigDecimal("7.50"));

        ProductResponse response = productService.update(1L, req);

        assertThat(response.getPrice()).isEqualByComparingTo("7.50");
        assertThat(response.getName()).isEqualTo("Old Name");
        assertThat(response.getDescription()).isEqualTo("Old Desc");
        assertThat(response.getSku()).isEqualTo("SKU-OLD");
        assertThat(response.getStockQuantity()).isEqualTo(3);
    }

    @Test
    void deleteById_whenExists_deletes() {
        when(productRepository.existsById(1L)).thenReturn(true);

        productService.deleteById(1L);

        verify(productRepository).deleteById(1L);
    }

    @Test
    void deleteById_whenNotExists_throwsResourceNotFoundException() {
        when(productRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> productService.deleteById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
        verify(productRepository, never()).deleteById(any());
    }
}
