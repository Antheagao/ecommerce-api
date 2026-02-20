package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.ProductRequest;
import com.antheagao.ecommerce_api.dto.ProductResponse;
import com.antheagao.ecommerce_api.entity.Category;
import com.antheagao.ecommerce_api.entity.Product;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.CategoryRepository;
import com.antheagao.ecommerce_api.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> findAllFiltered(Pageable pageable, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, String search) {
        return productRepository.findAllFiltered(categoryId, minPrice, maxPrice, search == null || search.isBlank() ? null : search.trim(), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findByCategoryId(Long categoryId) {
        return productRepository.findByCategoryId(categoryId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        return toResponse(p);
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        if (req.getSku() != null && !req.getSku().isBlank() && productRepository.existsBySku(req.getSku())) {
            throw new ConflictException("Product with SKU already exists: " + req.getSku());
        }
        Category category = req.getCategoryId() != null
                ? categoryRepository.findById(req.getCategoryId()).orElse(null)
                : null;
        Product product = Product.builder()
                .name(req.getName())
                .description(req.getDescription())
                .price(req.getPrice())
                .sku(req.getSku())
                .stockQuantity(req.getStockQuantity() != null ? req.getStockQuantity() : 0)
                .category(category)
                .build();
        product = productRepository.save(product);
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        if (req.getName() != null) product.setName(req.getName());
        if (req.getDescription() != null) product.setDescription(req.getDescription());
        if (req.getPrice() != null) product.setPrice(req.getPrice());
        if (req.getSku() != null) product.setSku(req.getSku());
        if (req.getStockQuantity() != null) product.setStockQuantity(req.getStockQuantity());
        if (req.getCategoryId() != null) {
            Category category = categoryRepository.findById(req.getCategoryId()).orElse(null);
            product.setCategory(category);
        }
        product = productRepository.save(product);
        return toResponse(product);
    }

    @Transactional
    public void deleteById(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product", id);
        }
        productRepository.deleteById(id);
    }

    private ProductResponse toResponse(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .sku(p.getSku())
                .stockQuantity(p.getStockQuantity())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : null)
                .categoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
