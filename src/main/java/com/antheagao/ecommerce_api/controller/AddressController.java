package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.AddressRequest;
import com.antheagao.ecommerce_api.dto.AddressResponse;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ResponseEntity<List<AddressResponse>> list(@AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(addressService.findByUserId(user.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AddressResponse> get(@PathVariable Long id, @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(addressService.findByIdAndUserId(id, user.id()));
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(@Valid @RequestBody AddressRequest request,
                                                   @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.create(user.id(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AddressResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody AddressRequest request,
                                                   @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(addressService.update(id, user.id(), request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal CurrentUser user) {
        addressService.deleteById(id, user.id());
        return ResponseEntity.noContent().build();
    }
}
