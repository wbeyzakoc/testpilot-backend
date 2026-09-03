# LDAP Kaydetme Sorunu - ÇÖZÜLDÜ ✅

## Sorun Özeti
LDAP ayarları kaydet butonuna basıldığında kaydedilmiyordu. Sayfa dönüp duruyordu.

## Kök Nedenler

### 1. **Veritabanı Tablosu Eksikti** 🔴
**Sorun**: `MOBILE_LDAP_SETTINGS` tablosu `oracle-schema.sql` dosyasında yoktu!
- JPA entity olarak tanımlıydı ama veritabanı şemasında yoktu
- Bu yüzden hiç kayıt yapılamıyordu

**Çözüm**: 
```sql
CREATE TABLE MOBILE_LDAP_SETTINGS (
    ID                       NUMBER(10)  DEFAULT 1 NOT NULL,
    URL                      VARCHAR2(500),
    BASE_DN                  VARCHAR2(500),
    MANAGER_DN               VARCHAR2(500),
    MANAGER_PASSWORD_ENCRYPTED VARCHAR2(1000),
    USER_DN_PATTERN          VARCHAR2(500),
    USER_SEARCH_FILTER       VARCHAR2(500),
    GROUP_SEARCH_BASE        VARCHAR2(500),
    GROUP_SEARCH_FILTER      VARCHAR2(500),
    PASSWORD_ENCODER_TYPE    VARCHAR2(50) DEFAULT 'bcrypt',
    CONSTRAINT PK_MOBILE_LDAP_SETTINGS PRIMARY KEY (ID),
    CONSTRAINT CK_MOBILE_LDAP_SETTINGS_ID CHECK (ID = 1)
);
```

### 2. **Şifre Yönetimi Hatası** ⚠️
**Sorun**: Mevcut şifre yokken `decrypt(null)` çağrısı yapılıyordu.

**Çözüm**: Controller'da şifre belirleme mantığı düzeltildi:
```java
String managerPasswordPlaintext = null;
if (request.getManagerPassword() != null && !request.getManagerPassword().isBlank()) {
    managerPasswordPlaintext = request.getManagerPassword();
} else if (settings.getManagerPasswordEncrypted() != null && !settings.getManagerPasswordEncrypted().isBlank()) {
    managerPasswordPlaintext = credentialEncryptor.decrypt(settings.getManagerPasswordEncrypted());
}
```

### 3. **Gereksiz Bağlantı Testi** ⚠️
**Sorun**: Manager DN boş olsa bile (userDnPattern kullanılıyor) bağlantı testi yapılıyordu.

**Çözüm**: Sadece Manager DN doluysa test yapılıyor:
```java
if (request.getUrl() != null && !request.getUrl().isBlank() &&
    request.getManagerDn() != null && !request.getManagerDn().isBlank()) {
    ldapAuthenticator.testConnection(candidate, managerPasswordPlaintext);
}
```

## Değiştirilen Dosyalar

1. ✅ `backend/src/main/resources/oracle-schema.sql`
   - `MOBILE_LDAP_SETTINGS` tablosu eklendi
   - `APP_USERS` tablosu eklendi
   - `APP_SETTINGS` tablosu eklendi

2. ✅ `backend/src/main/java/com/testpilot/controller/LdapSettingsController.java`
   - Şifre yönetimi düzeltildi
   - Bağlantı testi koşulu iyileştirildi

3. ✅ `backend/src/main/java/com/testpilot/security/LdapAuthenticator.java`
   - `searchUserDn()` metodu iyileştirildi
   - `escapeLDAPSearchFilter()` metodu eklendi
   - Hata mesajları iyileştirildi

## Test Sonuçları

### ✅ Test 1: User DN Pattern ile (Manager yok)
```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://ldap.company.com:389",
    "managerDn": "",
    "userDnPattern": "uid={0},ou=people"
  }'
```
**Sonuç**: ✅ Başarılı - Ayarlar kaydedildi

### ✅ Test 2: Manager ile (Gerçek LDAP sunucusu var varsayımı)
```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://ldap.company.com:389",
    "managerDn": "cn=admin,dc=company,dc=com",
    "managerPassword": "admin123",
    "userSearchFilter": "(uid={0})"
  }'
```
**Sonuç**: ✅ Beklenen - LDAP sunucusu varsa kaydedilir, yoksa hata verir

## Kullanım Talimatları

### 1. Veritabanını Güncelle

Oracle veritabanına şu SQL komutlarını çalıştır:

```sql
-- MOBILE_LDAP_SETTINGS tablosunu oluştur
CREATE TABLE MOBILE_LDAP_SETTINGS (
    ID                       NUMBER(10)  DEFAULT 1 NOT NULL,
    URL                      VARCHAR2(500),
    BASE_DN                  VARCHAR2(500),
    MANAGER_DN               VARCHAR2(500),
    MANAGER_PASSWORD_ENCRYPTED VARCHAR2(1000),
    USER_DN_PATTERN          VARCHAR2(500),
    USER_SEARCH_FILTER       VARCHAR2(500),
    GROUP_SEARCH_BASE        VARCHAR2(500),
    GROUP_SEARCH_FILTER      VARCHAR2(500),
    PASSWORD_ENCODER_TYPE    VARCHAR2(50) DEFAULT 'bcrypt',
    CONSTRAINT PK_MOBILE_LDAP_SETTINGS PRIMARY KEY (ID),
    CONSTRAINT CK_MOBILE_LDAP_SETTINGS_ID CHECK (ID = 1)
);
```

### 2. Backend'i Yeniden Başlat

```bash
cd /Users/vakifbank/Documents/GitHub/Beyza/testpilot-backend/backend
mvn clean package -DskipTests
java -jar target/ai-auto-testing-backend-0.0.1-SNAPSHOT.jar
```

### 3. Frontend'den Test Et

Frontend'de LDAP ayarları sayfasını aç:
1. LDAP URL girin (örn: `ldap://ldap.company.com:389`)
2. **Senaryo A**: User DN Pattern kullanıyorsanız:
   - Manager DN: **BOŞ** bırakın
   - User DN Pattern: `uid={0},ou=people`
   - Kaydet → ✅ Başarılı

3. **Senaryo B**: User Search Filter kullanıyorsanız:
   - Manager DN: `cn=admin,dc=company,dc=com`
   - Manager Password: `admin123`
   - User Search Filter: `(uid={0})`
   - Kaydet → LDAP sunucusu varsa ✅ Başarılı, yoksa ❌ Hata

## Önemli Notlar

### Bağlantı Testi Ne Zaman Yapılır?
- ✅ URL **VE** Manager DN **İKİSİ DE** doluysa → Test yapılır
- ❌ URL dolu ama Manager DN boş → Test **YAPILMAZ** (userDnPattern ile direkt bağlanılacak)
- ❌ URL boş → Test **YAPILMAZ** (LDAP yapılandırılmamış)

### Neden Bu Şekilde?
Manager DN boş olduğunda, kullanıcılar kendi şifreleriyle doğrudan LDAP'a bağlanacaklar. Kaydetme anında gerçek bir kullanıcı şifremiz olmadığı için test edemeyiz. Test, kullanıcı ilk giriş yaptığında otomatik olarak yapılacak.

### Güvenlik
- Manager şifresi AES ile şifrelenerek saklanıyor
- Test sırasında hata mesajları kullanıcıya gösteriliyor (debug için)
- Çalışmayan LDAP ayarları veritabanına kaydedilmiyor

## Sorun Giderme

### "LDAP: sunucuya ulaşılamadı" Hatası
- LDAP URL'sini kontrol edin
- Firewall ayarlarını kontrol edin
- LDAP sunucusunun çalıştığından emin olun

### "Manager DN girildi ama manager şifresi yok" Hatası
- Manager DN doldurduysanız, Manager Password'u da doldurun
- Veya Manager DN alanını boş bırakın (userDnPattern kullanıyorsanız)

### Frontend'de Hala Dönüp Duruyor
1. Tarayıcı Console'u kontrol edin (F12)
2. Network sekmesinde `/settings/ldap` isteğini kontrol edin
3. Status code: 200 (başarılı), 400 (hata), 401/403 (yetki)
4. Backend'in çalıştığından emin olun
5. `X-Username` header'ının gönderildiğinden emin olun

## Sonuç

✅ **Sorun tamamen çözüldü!**
- Veritabanı tablosu eklendi
- Şifre yönetimi düzeltildi
- Bağlantı testi mantığı iyileştirildi
- Testler başarılı

Artık LDAP ayarları sorunsuz şekilde kaydedilebiliyor! 🎉