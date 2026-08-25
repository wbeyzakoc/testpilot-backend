package com.testpilot.security;

import com.testpilot.model.LdapSettings;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.Hashtable;

// Şirket LDAP'ına karşı kullanıcı doğrulaması. Ekstra bir kütüphane (spring-ldap)
// eklemedik — JDK'nın kendi javax.naming (JNDI) LDAP istemcisi yeterli.
//
// İki yol destekleniyor (ldap.tsx'teki alanlara göre):
//   1) userDnPattern doluysa: DN doğrudan pattern'den kurulur (örn. "uid={0},ou=people").
//   2) Değilse userSearchFilter ile: önce manager hesabıyla bağlanılıp kullanıcı aranır,
//      bulunan DN ile kullanıcının kendi şifresiyle tekrar bağlanılarak doğrulanır.
//
// ldap.url boşsa (şirket LDAP'ı henüz yapılandırılmadıysa) her zaman false döner —
// hiçbir yere bağlanmaya çalışmaz.
@Component
public class LdapAuthenticator {

    private final CredentialEncryptor credentialEncryptor;

    public LdapAuthenticator(CredentialEncryptor credentialEncryptor) {
        this.credentialEncryptor = credentialEncryptor;
    }

    public boolean authenticate(LdapSettings settings, String username, String rawPassword) {
        if (settings == null || settings.getUrl() == null || settings.getUrl().isBlank()) return false;
        if (rawPassword == null || rawPassword.isBlank()) return false;
        try {
            String userDn = resolveUserDn(settings, username);
            if (userDn == null || userDn.isBlank()) return false;
            return bind(settings.getUrl(), userDn, rawPassword);
        } catch (NamingException e) {
            return false;
        }
    }

    private String resolveUserDn(LdapSettings settings, String username) throws NamingException {
        if (settings.getUserDnPattern() != null && !settings.getUserDnPattern().isBlank()) {
            String rdn = settings.getUserDnPattern().replace("{0}", username);
            String baseDn = settings.getBaseDn();
            return (baseDn == null || baseDn.isBlank()) ? rdn : rdn + "," + baseDn;
        }
        if (settings.getUserSearchFilter() != null && !settings.getUserSearchFilter().isBlank()) {
            return searchUserDn(settings, username);
        }
        return null;
    }

    private String searchUserDn(LdapSettings settings, String username) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, settings.getUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, settings.getManagerDn());
        env.put(Context.SECURITY_CREDENTIALS, credentialEncryptor.decrypt(settings.getManagerPasswordEncrypted()));

        DirContext managerCtx = new InitialDirContext(env);
        try {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            String filter = settings.getUserSearchFilter().replace("{0}", username);
            NamingEnumeration<SearchResult> results = managerCtx.search(
                    settings.getBaseDn() == null ? "" : settings.getBaseDn(), filter, controls);
            if (!results.hasMore()) return null;
            SearchResult result = results.next();
            return result.getNameInNamespace();
        } finally {
            managerCtx.close();
        }
    }

    private boolean bind(String url, String userDn, String password) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, userDn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        try {
            DirContext ctx = new InitialDirContext(env);
            ctx.close();
            return true;
        } catch (NamingException e) {
            return false;
        }
    }
}
