package com.antheagao.ecommerce_api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    @NotNull
    private Long shippingAddressId;

    /** If null, order is created from current cart. Otherwise use these line items. */
    private List<@Valid OrderLineRequest> items;
}
