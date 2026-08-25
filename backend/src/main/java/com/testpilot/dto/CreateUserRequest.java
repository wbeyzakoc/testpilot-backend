package com.testpilot.dto;

import com.testpilot.model.UserRole;

// POST /users gövdesi — superadmin'in elle LOCAL kullanıcı ekleyebilmesi için.
// LDAP kullanıcıları buradan değil, ilk LDAP girişinde otomatik oluşturulacak
// (o akış henüz bu adımın kapsamında değil).
public class CreateUserRequest {
    private String username;
    private String password;
    private UserRole role;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
}
