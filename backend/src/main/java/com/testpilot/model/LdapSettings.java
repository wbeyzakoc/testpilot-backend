package com.testpilot.model;

import jakarta.persistence.*;

// Tek satırlık ayar tablosu: uygulamanın tamamı için tek bir LDAP yapılandırması var,
// bu yüzden id her zaman sabit SINGLETON_ID değeriyle tutuluyor.
@Entity
@Table(name = "MOBILE_LDAP_SETTINGS")
public class LdapSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(length = 500)
    private String url;

    @Column(name = "base_dn", length = 500)
    private String baseDn;

    @Column(name = "manager_dn", length = 500)
    private String managerDn;

    // Manager şifresi geri döndürülebilir (LDAP'a bind olurken kullanılacak),
    // bu yüzden tek yönlü hash değil, AES ile şifrelenmiş metin olarak tutuluyor.
    // Bkz. com.testpilot.security.CredentialEncryptor
    @Column(name = "manager_password_encrypted", length = 1000)
    private String managerPasswordEncrypted;

    @Column(name = "user_dn_pattern", length = 500)
    private String userDnPattern;

    @Column(name = "user_search_filter", length = 500)
    private String userSearchFilter;

    @Column(name = "group_search_base", length = 500)
    private String groupSearchBase;

    @Column(name = "group_search_filter", length = 500)
    private String groupSearchFilter;

    @Column(name = "password_encoder_type", length = 50)
    private String passwordEncoderType = "bcrypt";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBaseDn() { return baseDn; }
    public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
    public String getManagerDn() { return managerDn; }
    public void setManagerDn(String managerDn) { this.managerDn = managerDn; }
    public String getManagerPasswordEncrypted() { return managerPasswordEncrypted; }
    public void setManagerPasswordEncrypted(String managerPasswordEncrypted) { this.managerPasswordEncrypted = managerPasswordEncrypted; }
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
