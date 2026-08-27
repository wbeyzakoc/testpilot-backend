package com.testpilot.security;

// LDAP dogrulamasi basarisiz oldugunda (yanlis sifre, sunucuya baglanilamadi,
// manager hesabi reddedildi, kullanici bulunamadi vb.) NEDENI tasiyan exception.
// Amac: ldap.tsx'te ayarlari kaydedip login denedigimizde, ekranda "kullanici
// adi/sifre hatali" gibi genel bir mesaj yerine gercek LDAP hatasini gormek --
// boylece hangi alanin (url, managerDn, userSearchFilter vb.) yanlis oldugunu
// backend konsoluna bakmadan, direkt login ekranindan anlayabiliyoruz.
public class LdapAuthException extends RuntimeException {
    public LdapAuthException(String message) {
        super(message);
    }

    public LdapAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
