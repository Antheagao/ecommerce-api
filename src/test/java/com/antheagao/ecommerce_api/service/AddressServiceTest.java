package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.AddressRequest;
import com.antheagao.ecommerce_api.dto.AddressResponse;
import com.antheagao.ecommerce_api.entity.Address;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.AddressRepository;
import com.antheagao.ecommerce_api.repository.UserRepository;
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
class AddressServiceTest {

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AddressService addressService;

    @Test
    void findByUserId_returnsMappedResponses() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(10L).street("1 Main St").city("Springfield").postalCode("12345").country("US").user(user).build();
        when(addressRepository.findByUserId(1L)).thenReturn(List.of(address));

        List<AddressResponse> responses = addressService.findByUserId(1L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(10L);
        assertThat(responses.get(0).getStreet()).isEqualTo("1 Main St");
    }

    @Test
    void findByIdAndUserId_whenOwnedByUser_returnsResponse() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(10L).street("1 Main St").user(user).build();
        when(addressRepository.findById(10L)).thenReturn(Optional.of(address));

        AddressResponse response = addressService.findByIdAndUserId(10L, 1L);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getStreet()).isEqualTo("1 Main St");
    }

    @Test
    void findByIdAndUserId_whenNotExists_throwsResourceNotFoundException() {
        when(addressRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.findByIdAndUserId(999L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
    }

    @Test
    void findByIdAndUserId_whenOwnedBySomeoneElse_throwsResourceNotFoundException() {
        User otherUser = User.builder().id(2L).build();
        Address address = Address.builder().id(10L).user(otherUser).build();
        when(addressRepository.findById(10L)).thenReturn(Optional.of(address));

        assertThatThrownBy(() -> addressService.findByIdAndUserId(10L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
    }

    @Test
    void create_whenValid_savesAndReturns() {
        User user = User.builder().id(1L).build();
        AddressRequest req = new AddressRequest();
        req.setStreet("1 Main St");
        req.setCity("Springfield");
        req.setPostalCode("12345");
        req.setCountry("US");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        Address saved = Address.builder().id(10L).street("1 Main St").city("Springfield").postalCode("12345").country("US").user(user).build();
        when(addressRepository.save(any(Address.class))).thenReturn(saved);

        AddressResponse response = addressService.create(1L, req);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getStreet()).isEqualTo("1 Main St");
        verify(addressRepository).save(any(Address.class));
    }

    @Test
    void create_whenUserNotFound_throwsResourceNotFoundException() {
        AddressRequest req = new AddressRequest();
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.create(999L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
        verify(addressRepository, never()).save(any());
    }

    @Test
    void update_whenOwnedByUser_updatesAndReturns() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(10L).street("Old St").city("Old City").postalCode("00000").country("US").user(user).build();
        AddressRequest req = new AddressRequest();
        req.setStreet("New St");
        req.setCity("New City");
        req.setPostalCode("11111");
        req.setCountry("CA");
        when(addressRepository.findById(10L)).thenReturn(Optional.of(address));
        when(addressRepository.save(any(Address.class))).thenReturn(address);

        AddressResponse response = addressService.update(10L, 1L, req);

        assertThat(response.getStreet()).isEqualTo("New St");
        assertThat(response.getCity()).isEqualTo("New City");
        assertThat(response.getPostalCode()).isEqualTo("11111");
        assertThat(response.getCountry()).isEqualTo("CA");
    }

    @Test
    void update_whenOwnedBySomeoneElse_throwsResourceNotFoundException() {
        User otherUser = User.builder().id(2L).build();
        Address address = Address.builder().id(10L).user(otherUser).build();
        AddressRequest req = new AddressRequest();
        req.setStreet("New St");
        when(addressRepository.findById(10L)).thenReturn(Optional.of(address));

        assertThatThrownBy(() -> addressService.update(10L, 1L, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
        verify(addressRepository, never()).save(any());
    }

    @Test
    void deleteById_whenOwnedByUser_deletes() {
        User user = User.builder().id(1L).build();
        Address address = Address.builder().id(10L).user(user).build();
        when(addressRepository.findById(10L)).thenReturn(Optional.of(address));

        addressService.deleteById(10L, 1L);

        verify(addressRepository).delete(address);
    }

    @Test
    void deleteById_whenOwnedBySomeoneElse_throwsResourceNotFoundException() {
        User otherUser = User.builder().id(2L).build();
        Address address = Address.builder().id(10L).user(otherUser).build();
        when(addressRepository.findById(10L)).thenReturn(Optional.of(address));

        assertThatThrownBy(() -> addressService.deleteById(10L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Address not found");
        verify(addressRepository, never()).delete(any(Address.class));
    }
}
