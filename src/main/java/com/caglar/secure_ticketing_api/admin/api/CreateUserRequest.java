package com.caglar.secure_ticketing_api.admin.api;

import java.util.Set;

import com.caglar.secure_ticketing_api.auth.domain.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

		@NotBlank @Email @Size(max = 255) String email,

		@NotBlank @Size(min = 8, max = 100) String password,

		@NotEmpty Set<Role> roles) {
}
