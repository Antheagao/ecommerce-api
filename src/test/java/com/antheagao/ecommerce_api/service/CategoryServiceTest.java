package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.CategoryRequest;
import com.antheagao.ecommerce_api.dto.CategoryResponse;
import com.antheagao.ecommerce_api.entity.Category;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void findById_whenExists_returnsResponse() {
        Category category = Category.builder().id(1L).name("Electronics").slug("electronics").build();
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

        CategoryResponse response = categoryService.findById(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Electronics");
        assertThat(response.getSlug()).isEqualTo("electronics");
    }

    @Test
    void findById_whenNotExists_throwsResourceNotFoundException() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.findById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category not found");
    }

    @Test
    void create_whenSlugExists_throwsConflictException() {
        CategoryRequest req = new CategoryRequest();
        req.setName("Test");
        req.setSlug("electronics");
        when(categoryRepository.existsBySlug("electronics")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("slug already exists");
    }

    @Test
    void create_whenValid_savesAndReturns() {
        CategoryRequest req = new CategoryRequest();
        req.setName("Electronics");
        req.setDescription("Tech stuff");
        when(categoryRepository.existsBySlug(any())).thenReturn(false);
        Category saved = Category.builder().id(1L).name("Electronics").slug("electronics").description("Tech stuff").build();
        when(categoryRepository.save(any())).thenReturn(saved);

        CategoryResponse response = categoryService.create(req);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Electronics");
        verify(categoryRepository).save(any(Category.class));
    }
}
