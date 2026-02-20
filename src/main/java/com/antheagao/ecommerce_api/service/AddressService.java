package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.AddressRequest;
import com.antheagao.ecommerce_api.dto.AddressResponse;
import com.antheagao.ecommerce_api.entity.Address;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.AddressRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<AddressResponse> findByUserId(Long userId) {
        return addressRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AddressResponse findByIdAndUserId(Long id, Long userId) {
        Address addr = addressRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Address", id));
        if (!addr.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Address", id);
        }
        return toResponse(addr);
    }

    @Transactional
    public AddressResponse create(Long userId, AddressRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Address address = Address.builder()
                .street(req.getStreet())
                .city(req.getCity())
                .stateOrProvince(req.getStateOrProvince())
                .postalCode(req.getPostalCode())
                .country(req.getCountry())
                .user(user)
                .build();
        address = addressRepository.save(address);
        return toResponse(address);
    }

    @Transactional
    public AddressResponse update(Long id, Long userId, AddressRequest req) {
        Address address = addressRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Address", id));
        if (!address.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Address", id);
        }
        if (req.getStreet() != null) address.setStreet(req.getStreet());
        if (req.getCity() != null) address.setCity(req.getCity());
        if (req.getStateOrProvince() != null) address.setStateOrProvince(req.getStateOrProvince());
        if (req.getPostalCode() != null) address.setPostalCode(req.getPostalCode());
        if (req.getCountry() != null) address.setCountry(req.getCountry());
        address = addressRepository.save(address);
        return toResponse(address);
    }

    @Transactional
    public void deleteById(Long id, Long userId) {
        Address address = addressRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Address", id));
        if (!address.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Address", id);
        }
        addressRepository.delete(address);
    }

    private AddressResponse toResponse(Address a) {
        return AddressResponse.builder()
                .id(a.getId())
                .street(a.getStreet())
                .city(a.getCity())
                .stateOrProvince(a.getStateOrProvince())
                .postalCode(a.getPostalCode())
                .country(a.getCountry())
                .build();
    }
}
