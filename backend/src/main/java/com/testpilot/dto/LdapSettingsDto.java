package com.testpilot.dto;

import com.testpilot.model.LdapSettings;

// GET /settings/ldap yanıtı — ldap.tsx'teki LdapSettings tipiyle birebir eşleşir.
// Manager şifresi hiçbir zaman düz metin olarak dönmez, sadece set edilip
// edilmediği (managerPasswordSet) bilgisi gönderilir.
public class LdapSettingsDto {
    private String url;
    private String baseDn;
    private String managerDn;
    private boolean managerPasswordSet;
    private String userDnPattern;
    private String userSearchFilter;
    private String groupSearchBase;
    private String groupSearchFilter;
    private String passwordEncoderType;

    public static LdapSettingsDto from(LdapSettings s) {
        LdapSettingsDto dto = new LdapSettingsDto();
        dto.url = s.getUrl();
        dto.baseDn = s.getBaseDn();
        dto.managerDn = s.getManagerDn();
        dto.managerPasswordSet = s.getManagerPasswordEncrypted() != null && !s.getManagerPasswordEncrypted().isBlank();
        dto.userDnPattern = s.getUserDnPattern();
        dto.userSearchFilter = s.getUserSearchFilter();
        dto.groupSearchBase = s.getGroupSearchBase();
        dto.groupSearchFilter = s.getGroupSearchFilter();
        dto.passwordEncoderType = s.getPasswordEncoderType();
        return dto;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
    public String getManagerDn() { return managerDn; }
    public void setManagerDn(String managerDn) { this.managerDn = managerDn; }
    public boolean isManagerPasswordSet() { return managerPasswordSet; }
    public void setManagerPasswordSet(boolean managerPasswordSet) { this.managerPasswordSet = managerPasswordSet; }
    public String getUserDnPattern() { return userDnPattern; }
    public void setUserDnPattern(String userDnPattern) { this.userDnPattern = userDnPattern; }
    public String getUserSearchFilter() { return userSearchFilter; }
    public void setUserSearchFilter(String userSearchFilter) { this.userSearchFilter = userSearchFilter; }
    public String getGroupSearchBase() { return groupSearchBase; }
    public void setGroupSearchBase(String groupSearchBase) { this.groupSearchBase = groupSearchBase; }
    public String getGroupSearchFilter() { return groupSearchFilter; }
    public void setGroupSearchFilter(String groupSearchFilter) { this.groupSearchFilter = groupSearchFilter; }
    public String getPasswordEncoderType() { return passwordEncoderType; }
    public void setPasswordEncoderType(String passwordEncoderType) { this.passwordEncoderType = passwordEncoderType; }
}
