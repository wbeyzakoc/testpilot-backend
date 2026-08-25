package com.testpilot.dto;

import com.testpilot.model.UserRole;

// login.tsx'in beklediği { username, role } yanıtı.
public class LoginResponse {
    private String username;
    private UserRole role;

    public LoginResponse() { }
    public LoginResponse(String username, UserRole role) {
        this.username = username;
        this.role = role;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
}
