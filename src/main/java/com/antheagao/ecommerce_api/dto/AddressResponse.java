package com.antheagao.ecommerce_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddressResponse {

    private Long id;
    private String street;
    private String city;
    private String stateOrProvince;
    private String postalCode;
    private String country;
}
