package com.testpilot.security;

import com.testpilot.model.LdapSettings;
import org.springframework.stereotype.Component;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.Hashtable;

// Sirket LDAP'ina karsi kullanici dogrulamasi. Ekstra bir kutuphane (spring-ldap)
// eklemedik -- JDK'nin kendi javax.naming (JNDI) LDAP istemcisi yeterli.
//
// Iki yol destekleniyor (ldap.tsx'teki alanlara gore):
//   1) userDnPattern doluysa: DN dogrudan pattern'den kurulur (ornek: "uid={0},ou=people").
//   2) Degilse userSearchFilter ile: once manager hesabiyla baglanilip kullanici aranir,
//      bulunan DN ile kullanicinin kendi sifresiyle tekrar baglanilarak dogrulanir.
//
// ldap.url bossa (sirket LDAP'i henuz yapilandirilmadiysa) her zaman false doner --
// hicbir yere baglanmaya calismaz. url DOLUYSA ve dogrulama basarisiz olursa artik
// sessizce false donmuyoruz -- LdapAuthException firlatiyoruz, gercek nedeni
// (baglanti hatasi, yanlis manager sifresi, kullanici bulunamadi, yanlis sifre vb.)
// tasiyan bir mesajla. AuthController bunu yakalayip login ekranina yansitiyor --
// boylece LDAP ayarlarini kaydedip ilk denemede bir sey ters giderse, hatayi
// backend konsoluna bakmadan direkt ekranda gorebiliyoruz.
@Component
public class LdapAuthenticator {

    private final CredentialEncryptor credentialEncryptor;

    public LdapAuthenticator(CredentialEncryptor credentialEncryptor) {
        this.credentialEncryptor = credentialEncryptor;
    }

    // Ayarlar panelden (ldap.tsx -> PUT /settings/ldap) kaydedilmeden ONCE
    // baglantiyi test etmek icin -- login akisindaki authenticate()'ten farkli
    // olarak burada gercek bir kullanici sifresi yok, sadece "bu ayarlarla
    // LDAP sunucusuna gercekten ulasip dogrulanabiliyor muyuz" kontrol ediliyor.
    // Basarisizsa (authenticate() gibi) LdapAuthException firlatir -- controller
    // bunu yakalayip 400 doner ve ayarlari VERITABANINA KAYDETMEZ. Boylece yanlis/
    // calismayan bir LDAP ayari kaydedilip sonraki tum giris denemelerini
    // sessizce bozmuyor.
    public void testConnection(LdapSettings settings, String managerPasswordPlaintext) {
        if (settings.getUrl() == null || settings.getUrl().isBlank()) {
            throw new LdapAuthException("LDAP URL boş olamaz.");
        }

        if (settings.getManagerDn() != null && !settings.getManagerDn().isBlank()) {
            // Manager hesabıyla bağlanıyoruz -- URL, managerDn ve manager şifresinin
            // üçünün de doğru olduğunu tek seferde doğrular (en yaygın kurulum şekli:
            // userSearchFilter + manager).
            if (managerPasswordPlaintext == null || managerPasswordPlaintext.isBlank()) {
                throw new LdapAuthException(
                        "Manager DN girildi ama manager şifresi yok -- test için ikisi de gerekli.");
            }
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, settings.getUrl());
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, settings.getManagerDn());
            env.put(Context.SECURITY_CREDENTIALS, managerPasswordPlaintext);
            try {
                DirContext ctx = new InitialDirContext(env);
                ctx.close();
            } catch (AuthenticationException e) {
                throw new LdapAuthException(
                        "LDAP: manager hesabıyla bağlanılamadı -- kimlik bilgileri reddedildi. Detay: "
                                + e.getMessage(), e);
            } catch (CommunicationException e) {
                throw new LdapAuthException(
                        "LDAP: sunucuya ulaşılamadı (" + settings.getUrl() + ") -- adres/port dogru mu, sunucu ayakta mi? Detay: "
                                + e.getMessage(), e);
            } catch (NamingException e) {
                throw new LdapAuthException("LDAP: bağlantı test edilemedi. Detay: " + e.getMessage(), e);
            }
        } else {
            // Manager tanımlı değil (muhtemelen userDnPattern ile doğrudan bind
            // kullanılacak) -- gerçek bir kullanıcı şifremiz olmadığı için tek
            // yapabildiğimiz sunucuya en azından ulaşılabildiğini doğrulamak.
            // Anonim bağlantı bazı sunucularda reddedilir, bu normal ve kritik
            // değil -- asıl aradığımız CommunicationException (sunucu hiç yanıt
            // vermiyor/adres yanlış).
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, settings.getUrl());
            try {
                DirContext ctx = new InitialDirContext(env);
                ctx.close();
            } catch (CommunicationException e) {
                throw new LdapAuthException(
                        "LDAP: sunucuya ulaşılamadı (" + settings.getUrl() + ") -- adres/port dogru mu, sunucu ayakta mi? Detay: "
                                + e.getMessage(), e);
            } catch (NamingException ignored) {
                // anonim bağlantı reddedildi ama sunucu yanıt verdi -- bu asamada yeterli
            }
        }
    }

    public boolean authenticate(LdapSettings settings, String username, String rawPassword) {
        if (settings == null || settings.getUrl() == null || settings.getUrl().isBlank()) return false;
        if (rawPassword == null || rawPassword.isBlank()) return false;

        String userDn = resolveUserDn(settings, username);
        if (userDn == null || userDn.isBlank()) {
            throw new LdapAuthException(
                    "LDAP: \"" + username + "\" kullanici adiyla eslesen bir kayit bulunamadi "
                            + "(userDnPattern ve userSearchFilter ayarlarini kontrol et).");
        }
        bind(settings.getUrl(), userDn, rawPassword);
        return true;
    }

    private String resolveUserDn(LdapSettings settings, String username) {
        if (settings.getUserDnPattern() != null && !settings.getUserDnPattern().isBlank()) {
            String rdn = settings.getUserDnPattern().replace("{0}", username);
            String baseDn = settings.getBaseDn();
            return (baseDn == null || baseDn.isBlank()) ? rdn : rdn + "," + baseDn;
        }
        if (settings.getUserSearchFilter() != null && !settings.getUserSearchFilter().isBlank()) {
            return searchUserDn(settings, username);
        }
        throw new LdapAuthException(
                "LDAP ayarlarinda ne userDnPattern ne de userSearchFilter dolu -- ldap.tsx panelinden en az birini girmen lazim.");
    }

    private String searchUserDn(LdapSettings settings, String username) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, settings.getUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, settings.getManagerDn());
        env.put(Context.SECURITY_CREDENTIALS, credentialEncryptor.decrypt(settings.getManagerPasswordEncrypted()));

        DirContext managerCtx;
        try {
            managerCtx = new InitialDirContext(env);
        } catch (AuthenticationException e) {
            throw new LdapAuthException(
                    "LDAP: manager hesabiyla (managerDn/managerPassword) baglanilamadi -- kimlik bilgileri reddedildi. Detay: "
                            + e.getMessage(), e);
        } catch (CommunicationException e) {
            throw new LdapAuthException(
                    "LDAP: sunucuya ulasilamadi (" + settings.getUrl() + ") -- adres/port dogru mu, sunucu ayakta mi? Detay: "
                            + e.getMessage(), e);
        } catch (NamingException e) {
            throw new LdapAuthException("LDAP: manager baglantisi kurulamadi. Detay: " + e.getMessage(), e);
        }

        try {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            String filter = settings.getUserSearchFilter().replace("{0}", username);
            NamingEnumeration<SearchResult> results = managerCtx.search(
                    settings.getBaseDn() == null ? "" : settings.getBaseDn(), filter, controls);
            if (!results.hasMore()) return null;
            SearchResult result = results.next();
            return result.getNameInNamespace();
        } catch (NamingException e) {
            throw new LdapAuthException(
                    "LDAP: kullanici aranirken hata olustu (baseDn/userSearchFilter'i kontrol et). Detay: "
                            + e.getMessage(), e);
        } finally {
            try {
                managerCtx.close();
            } catch (NamingException ignored) {
                // baglanti zaten kapaniyor olabilir, yoksayilir
            }
        }
    }

    private void bind(String url, String userDn, String password) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, userDn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        try {
            DirContext ctx = new InitialDirContext(env);
            ctx.close();
        } catch (AuthenticationException e) {
            throw new LdapAuthException("LDAP: kullanici adi veya parola LDAP tarafindan reddedildi.", e);
        } catch (CommunicationException e) {
            throw new LdapAuthException(
                    "LDAP: sunucuya ulasilamadi (" + url + ") -- adres/port dogru mu, sunucu ayakta mi? Detay: "
                            + e.getMessage(), e);
        } catch (NamingException e) {
            throw new LdapAuthException("LDAP: kullanici dogrulanamadi. Detay: " + e.getMessage(), e);
        }
    }
}
