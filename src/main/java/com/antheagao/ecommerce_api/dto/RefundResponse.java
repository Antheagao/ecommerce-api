package com.antheagao.ecommerce_api.dto;

import com.antheagao.ecommerce_api.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundResponse {

    private Long orderId;
    private String orderNumber;
    private String refundId;
    private String refundStatus;
    private OrderStatus orderStatus;
}
