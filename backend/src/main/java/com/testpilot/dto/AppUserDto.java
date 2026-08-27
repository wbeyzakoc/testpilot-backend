package com.testpilot.dto;

import com.testpilot.model.AppUser;
import com.testpilot.model.UserRole;
import com.testpilot.model.UserSource;

// Frontend'e (users.tsx) dönen görünüm — passwordHash asla dışarı sızmaz.
// testCount/passedCount/successRate, AppUser'ın parçası değil -- UserController
// bunları Run.createdBy üzerinden ayrıca hesaplayıp buraya dolduruyor.
public class AppUserDto {
    private String id;
    private String username;
    private UserRole role;
    private UserSource source;
    private int testCount;
    private int passedCount;
    private Double successRate; // testCount 0 ise null (yüzde hesaplanamaz)

    public static AppUserDto from(AppUser u) {
        AppUserDto dto = new AppUserDto();
        dto.id = String.valueOf(u.getId());
        dto.username = u.getUsername();
        dto.role = u.getRole();
        dto.source = u.getSource();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
    public UserSource getSource() { return source; }
    public void setSource(UserSource source) { this.source = source; }
    public int getTestCount() { return testCount; }
    public void setTestCount(int testCount) { this.testCount = testCount; }
    public int getPassedCount() { return passedCount; }
    public void setPassedCount(int passedCount) { this.passedCount = passedCount; }
    public Double getSuccessRate() { return successRate; }
    public void setSuccessRate(Double successRate) { this.successRate = successRate; }
}
