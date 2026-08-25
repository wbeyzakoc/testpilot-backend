package com.testpilot.dto;

// PUT /settings/ldap gövdesi — ldap.tsx handleSave() ile birebir eşleşir.
// managerPassword sadece kullanıcı değiştirmek istediğinde dolu gelir (nullable).
public class UpdateLdapSettingsRequest {
    private String url;
    private String baseDn;
    private String managerDn;
    private String managerPassword;
    private String userDnPattern;
    private String userSearchFilter;
    private String groupSearchBase;
    private String groupSearchFilter;
    private String passwordEncoderType;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
    public String getManagerDn() { return managerDn; }
    public void setManagerDn(String managerDn) { this.managerDn = managerDn; }
    public String getManagerPassword() { return managerPassword; }
    public void setManagerPassword(String managerPassword) { this.managerPassword = managerPassword; }
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
