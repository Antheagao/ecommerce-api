package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.CategoryRequest;
import com.antheagao.ecommerce_api.dto.CategoryResponse;
import com.antheagao.ecommerce_api.entity.Category;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return categoryRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<CategoryResponse> findAll(Pageable pageable) {
        return categoryRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        Category cat = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        return toResponse(cat);
    }

    @Transactional
    public CategoryResponse create(CategoryRequest req) {
        String slug = req.getSlug() != null && !req.getSlug().isBlank()
                ? req.getSlug()
                : req.getName().toLowerCase().replaceAll("\\s+", "-");
        if (categoryRepository.existsBySlug(slug)) {
            throw new ConflictException("Category with slug already exists: " + slug);
        }
        Category parent = req.getParentId() != null
                ? categoryRepository.findById(req.getParentId()).orElse(null)
                : null;
        Category category = Category.builder()
                .name(req.getName())
                .slug(slug)
                .parentCategory(parent)
                .description(req.getDescription())
                .build();
        category = categoryRepository.save(category);
        return toResponse(category);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest req) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        if (req.getName() != null) category.setName(req.getName());
        if (req.getDescription() != null) category.setDescription(req.getDescription());
        if (req.getParentId() != null) {
            Category parent = categoryRepository.findById(req.getParentId()).orElse(null);
            category.setParentCategory(parent);
        }
        category = categoryRepository.save(category);
        return toResponse(category);
    }

    @Transactional
    public void deleteById(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Category", id);
        }
        categoryRepository.deleteById(id);
    }

    private CategoryResponse toResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .slug(c.getSlug())
                .parentId(c.getParentCategory() != null ? c.getParentCategory().getId() : null)
                .description(c.getDescription())
                .build();
    }
}
