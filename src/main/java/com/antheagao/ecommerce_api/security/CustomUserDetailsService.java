package com.antheagao.ecommerce_api.security;

import com.antheagao.ecommerce_api.entity.User;
import com.antheagao.ecommerce_api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        String role = user.getRole() != null ? user.getRole().toUpperCase() : "USER";
        if (!role.startsWith("ROLE_")) role = "ROLE_" + role;
        List<SimpleGrantedAuthority> authorities = Stream.of(role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        return new CurrentUser(user.getId(), user.getEmail(), user.getPasswordHash(), authorities);
    }
}
