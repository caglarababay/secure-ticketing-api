package com.caglar.secure_ticketing_api.admin.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.caglar.secure_ticketing_api.admin.service.AdminUserService;
import com.caglar.secure_ticketing_api.auth.api.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

	private final AdminUserService adminUsers;

	AdminUserController(AdminUserService adminUsers) {
		this.adminUsers = adminUsers;
	}

	@Operation(summary = "Open an account with chosen roles", description = "ADMIN only.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Account created with the given roles"),
			@ApiResponse(responseCode = "400", description = "Invalid address, short password, or no roles"),
			@ApiResponse(responseCode = "403", description = "Caller is not an ADMIN"),
			@ApiResponse(responseCode = "409", description = "Address already registered") })
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ADMIN')")
	UserResponse create(@Valid @RequestBody CreateUserRequest request) {
		return UserResponse.from(
				adminUsers.create(request.email(), request.password(), request.roles()));
	}
}
