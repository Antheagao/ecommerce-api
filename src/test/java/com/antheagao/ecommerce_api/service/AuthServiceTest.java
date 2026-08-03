package com.antheagao.ecommerce_api.service;

import com.antheagao.ecommerce_api.dto.AuthResponse;
import com.antheagao.ecommerce_api.dto.LoginRequest;
import com.antheagao.ecommerce_api.dto.RegisterRequest;
import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.exception.ConflictException;
import com.antheagao.ecommerce_api.exception.ResourceNotFoundException;
import com.antheagao.ecommerce_api.repository.UserRepository;
import com.antheagao.ecommerce_api.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_whenValid_encodesPasswordSavesUserAndReturnsToken() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@example.com");
        req.setPassword("password123");
        req.setFirstName("Jane");
        req.setLastName("Doe");

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-pw");
        User saved = User.builder().id(1L).email("new@example.com").passwordHash("hashed-pw").build();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(userCaptor.capture())).thenReturn(saved);
        when(jwtUtil.generateToken("new@example.com", 1L)).thenReturn("token123");

        AuthResponse response = authService.register(req);

        assertThat(response.getToken()).isEqualTo("token123");
        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("new@example.com");
        verify(passwordEncoder).encode("password123");
        // Guards against a bug that encodes the password but forgets to use the encoded value --
        // e.g. `passwordHash(req.getPassword())` instead of `passwordHash(passwordEncoder.encode(...))`.
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed-pw");
    }

    @Test
    void register_whenEmailAlreadyExists_throwsConflictException() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("taken@example.com");
        req.setPassword("password123");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email already registered");
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_whenValid_returnsToken() {
        LoginRequest req = new LoginRequest();
        req.setEmail("user@example.com");
        req.setPassword("password123");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@example.com", null);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        User user = User.builder().id(5L).email("user@example.com").build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("user@example.com", 5L)).thenReturn("token456");

        AuthResponse response = authService.login(req);

        assertThat(response.getToken()).isEqualTo("token456");
        assertThat(response.getUserId()).isEqualTo(5L);
        assertThat(response.getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void login_whenUserNotFound_throwsResourceNotFoundException() {
        LoginRequest req = new LoginRequest();
        req.setEmail("ghost@example.com");
        req.setPassword("password123");

        Authentication auth = new UsernamePasswordAuthenticationToken("ghost@example.com", null);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
    }
}
