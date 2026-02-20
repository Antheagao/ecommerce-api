package com.antheagao.ecommerce_api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddressRequest {

    @NotBlank
    private String street;

    @NotBlank
    private String city;

    private String stateOrProvince;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String country;
}
