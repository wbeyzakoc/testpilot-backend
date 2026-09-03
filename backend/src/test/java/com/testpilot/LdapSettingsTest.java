package com.testpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import com.testpilot.repository.LdapSettingsRepository;
import com.testpilot.model.LdapSettings;

/**
 * LDAP Ayarları Test Uygulaması
 * Bu sınıf, LDAP ayarlarının veritabanına kaydedilip kaydedilemediğini test eder.
 * 
 * Çalıştırma:
 *   mvn exec:java -Dexec.mainClass="com.testpilot.LdapSettingsTest"
 * veya
 *   java -cp target/classes:target/dependency/* com.testpilot.LdapSettingsTest
 */
@SpringBootApplication
public class LdapSettingsTest {

    public static void main(String[] args) {
        System.out.println("=== LDAP Ayarları Testi Başlatılıyor ===\n");
        
        ConfigurableApplicationContext context = SpringApplication.run(LdapSettingsTest.class, args);
        
        try {
            LdapSettingsRepository repository = context.getBean(LdapSettingsRepository.class);
            
            // 1. Mevcut ayarları getir
            System.out.println("1. Mevcut LDAP ayarları getiriliyor...");
            LdapSettings settings = repository.findById(LdapSettings.SINGLETON_ID)
                .orElseGet(() -> {
                    System.out.println("   → Kayıt bulunamadı, yeni kayıt oluşturuluyor...");
                    LdapSettings newSettings = new LdapSettings();
                    newSettings.setId(LdapSettings.SINGLETON_ID);
                    return newSettings;
                });
            
            System.out.println("   ✓ Mevcut ayarlar:");
            System.out.println("     - ID: " + settings.getId());
            System.out.println("     - URL: " + settings.getUrl());
            System.out.println("     - Base DN: " + settings.getBaseDn());
            System.out.println("     - Manager DN: " + settings.getManagerDn());
            System.out.println("     - User DN Pattern: " + settings.getUserDnPattern());
            System.out.println("     - User Search Filter: " + settings.getUserSearchFilter());
            
            // 2. Ayarları güncelle
            System.out.println("\n2. LDAP ayarları güncelleniyor...");
            settings.setUrl("ldap://localhost:389");
            settings.setBaseDn("dc=company,dc=com");
            settings.setManagerDn("cn=admin,dc=company,dc=com");
            settings.setManagerPasswordEncrypted("test_encrypted_password_123");
            settings.setUserDnPattern("uid={0},ou=people");
            settings.setUserSearchFilter("(uid={0})");
            settings.setGroupSearchBase("ou=groups,dc=company,dc=com");
            settings.setGroupSearchFilter("memberUid={0}");
            settings.setPasswordEncoderType("bcrypt");
            
            System.out.println("   ✓ Ayarlar güncellendi");
            
            // 3. Kaydet
            System.out.println("\n3. Ayarlar veritabanına kaydediliyor...");
            LdapSettings saved = repository.save(settings);
            System.out.println("   ✓ Kayıt başarılı!");
            System.out.println("     - Kayıt ID: " + saved.getId());
            
            // 4. Güncellenmiş ayarları tekrar getir
            System.out.println("\n4. Güncellenmiş ayarlar kontrol ediliyor...");
            LdapSettings reloaded = repository.findById(LdapSettings.SINGLETON_ID)
                .orElseThrow(() -> new RuntimeException("❌ Kayıt bulunamadı!"));
            
            System.out.println("   ✓ Güncellenmiş ayarlar:");
            System.out.println("     - URL: " + reloaded.getUrl());
            System.out.println("     - Base DN: " + reloaded.getBaseDn());
            System.out.println("     - Manager DN: " + reloaded.getManagerDn());
            System.out.println("     - Manager Password Set: " + (reloaded.getManagerPasswordEncrypted() != null));
            System.out.println("     - User DN Pattern: " + reloaded.getUserDnPattern());
            System.out.println("     - User Search Filter: " + reloaded.getUserSearchFilter());
            System.out.println("     - Group Search Base: " + reloaded.getGroupSearchBase());
            System.out.println("     - Group Search Filter: " + reloaded.getGroupSearchFilter());
            
            // 5. Doğrulama
            System.out.println("\n5. Doğrulama yapılıyor...");
            boolean success = true;
            
            if (!"ldap://localhost:389".equals(reloaded.getUrl())) {
                System.out.println("   ❌ URL eşleşmiyor!");
                success = false;
            }
            
            if (!"dc=company,dc=com".equals(reloaded.getBaseDn())) {
                System.out.println("   ❌ Base DN eşleşmiyor!");
                success = false;
            }
            
            if (!"cn=admin,dc=company,dc=com".equals(reloaded.getManagerDn())) {
                System.out.println("   ❌ Manager DN eşleşmiyor!");
                success = false;
            }
            
            if (reloaded.getManagerPasswordEncrypted() == null) {
                System.out.println("   ❌ Manager şifresi kaydedilmemiş!");
                success = false;
            }
            
            if (!"uid={0},ou=people".equals(reloaded.getUserDnPattern())) {
                System.out.println("   ❌ User DN Pattern eşleşmiyor!");
                success = false;
            }
            
            if (success) {
                System.out.println("   ✓ Tüm kontroller başarılı!");
            }
            
            System.out.println("\n=== Test " + (success ? "BAŞARILI" : "BAŞARISIZ") + " ===");
            System.exit(success ? 0 : 1);
            
        } catch (Exception e) {
            System.err.println("\n❌ Test sırasında hata: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            context.close();
        }
    }
}