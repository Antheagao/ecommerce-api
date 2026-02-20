package com.antheagao.ecommerce_api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    @DecimalMin("0")
    private BigDecimal price;

    private String sku;

    private Integer stockQuantity;
    private Long categoryId;
}
