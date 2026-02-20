package com.antheagao.ecommerce_api.dto;

import com.antheagao.ecommerce_api.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderStatusUpdateRequest {

    @NotNull
    private OrderStatus status;

    private String paymentReference;
}
