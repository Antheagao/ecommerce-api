package com.antheagao.ecommerce_api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CategoryRequest {

    @NotBlank
    private String name;

    private String slug;
    private Long parentId;
    private String description;
}
