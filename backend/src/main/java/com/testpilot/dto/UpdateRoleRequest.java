package com.testpilot.dto;

import com.testpilot.model.UserRole;

public class UpdateRoleRequest {
    private UserRole role;

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
}
