package com.caglar.secure_ticketing_api.auth.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import com.caglar.secure_ticketing_api.auth.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	AuthController(AuthService authService) {
		this.authService = authService;
	}

	@Operation(summary = "Open an account")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Account created"),
			@ApiResponse(responseCode = "409", description = "Address already registered"),
			@ApiResponse(responseCode = "429", description = "Too many attempts from this address",
					headers = @Header(name = "Retry-After", description = "Seconds to wait",
							schema = @Schema(type = "integer"))) })
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return UserResponse.from(authService.register(request));
	}

	@Operation(summary = "Exchange credentials for tokens")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Access and refresh tokens"),
			@ApiResponse(responseCode = "401", description = "Invalid email or password"),
			@ApiResponse(responseCode = "429", description = "Too many attempts from this address",
					headers = @Header(name = "Retry-After", description = "Seconds to wait",
							schema = @Schema(type = "integer"))) })
	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@Operation(summary = "Trade a refresh token for a new access token")
	@PostMapping("/refresh")
	TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request.refreshToken());
	}

	@Operation(summary = "The signed-in account")
	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal String userId) {
		return UserResponse.from(authService.requireById(Long.valueOf(userId)));
	}
}
