package com.antheagao.ecommerce_api.controller;

import com.antheagao.ecommerce_api.dto.UserResponse;
import com.antheagao.ecommerce_api.security.CurrentUser;
import com.antheagao.ecommerce_api.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(userService.getProfile(currentUser.id()));
    }
}
