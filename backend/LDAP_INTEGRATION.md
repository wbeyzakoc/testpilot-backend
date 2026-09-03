# LDAP Entegrasyonu - Test ve Sorun Giderme

## Yapılan İyileştirmeler

### 1. LDAP Arama Filtresi Güvenliği
- **Sorun**: Kullanıcı adı içinde özel karakterler (`*`, `\`, `(`, `)`) LDAP arama filtrelerinde hatalı sonuçlara veya güvenlik açıklarına neden olabilirdi.
- **Çözüm**: `escapeLDAPSearchFilter()` metodu eklendi. RFC 4515 standardına göre özel karakterleri escape eder.

### 2. DN Çözümleme Güvenilirliği
- **Sorun**: `getNameInNamespace()` bazen tam DN yerine beklenmedik formatlar döndürebilir.
- **Çözüm**: `getName()` kullanılarak daha tutarlı sonuçlar elde edildi. Eksik baseDn bilgisi otomatik olarak tamamlandı.

### 3. Çoklu Sonuç Yönetimi
- **Sorun**: Birden fazla kullanıcı bulunduğunda sadece ilk sonuç kullanılıyordu, ancak bu durum takip edilmiyordu.
- **Çözüm**: Çoklu sonuç durumunda uyarı mekanizması eklendi (loglama için hazır).

## LDAP Yapılandırma Örnekleri

### Örnek 1: Active Directory
```
URL: ldap://ad.company.com:389
Manager DN: CN=Administrator,CN=Users,DC=company,DC=com
Manager Password: [şifre]
Base DN: DC=company,DC=com
User DN Pattern: {0}@company.com
User Search Filter: (sAMAccountName={0})
Group Search Base: DC=company,DC=com
Group Search Filter: (member={0})
Password Encoder Type: bcrypt
```

### Örnek 2: OpenLDAP
```
URL: ldap://ldap.company.com:389
Manager DN: cn=admin,dc=company,dc=com
Manager Password: [şifre]
Base DN: dc=company,dc=com
User DN Pattern: uid={0},ou=people
User Search Filter: (uid={0})
Group Search Base: ou=groups,dc=company,dc=com
Group Search Filter: (memberUid={0})
Password Encoder Type: bcrypt
```

### Örnek 3: User Search Filter ile (DN Pattern yok)
```
URL: ldap://ldap.company.com:389
Manager DN: cn=admin,dc=company,dc=com
Manager Password: [şifre]
Base DN: dc=company,dc=com
User DN Pattern: [boş]
User Search Filter: (uid={0})
Group Search Base: ou=groups,dc=company,dc=com
Group Search Filter: (memberUid={0})
Password Encoder Type: bcrypt
```

## Sorun Giderme

### Kaydet Butonu Çalışmıyor (Dönüp Duruyor)

#### 1. Backend Tarafında Kontrol Edilmesi Gerekenler

**Sorun**: `testConnection` çağrısında mevcut şifre yokken `decrypt(null)` çağrısı yapılıyordu.

**Çözüm**: Controller'da şifre belirleme mantığı düzeltildi:
- Yeni şifre gönderildiyse → yeni şifreyi kullan
- Yeni şifre yok ama mevcut şifre varsa → mevcut şifreyi kullan  
- Ne yeni ne mevcut şifre yoksa → null (managerDn boşsa sorun yok, doluysa hata verir)

#### 2. Frontend Tarafında Kontrol Edilmesi Gerekenler

Frontend'de `ldap.tsx` dosyasında şu noktaları kontrol edin:

```typescript
// 1. API URL'inin doğru olduğundan emin olun
const API_URL = 'http://localhost:4000'; // veya backend portunuz

// 2. Yetki header'ının gönderildiğinden emin olun
const response = await fetch(`${API_URL}/settings/ldap`, {
  method: 'PUT',
  headers: {
    'Content-Type': 'application/json',
    'X-Username': currentUser?.username || '', // Admin yetkisi için gerekli
  },
  body: JSON.stringify(ldapSettings),
});

// 3. Hata yakalama olup olmadığını kontrol edin
try {
  const response = await fetch(...);
  if (!response.ok) {
    const error = await response.text();
    throw new Error(error);
  }
  const data = await response.json();
  // Başarılı
} catch (error) {
  console.error('LDAP ayarları kaydedilemedi:', error);
  // Hata mesajını kullanıcıya göster
}
```

#### 3. Network Request'i Tarayıcıda İzleme

1. Tarayıcıda F12 → Network sekmesi
2. LDAP ayarlarını kaydet butonuna basın
3. `/settings/ldap` isteğini bulun
4. Şu bilgileri kontrol edin:
   - **Request URL**: `http://localhost:4000/settings/ldap`
   - **Request Method**: `PUT`
   - **Status Code**: `200 OK` veya `400 Bad Request` veya `401 Unauthorized`
   - **Request Headers**: `X-Username` var mı?
   - **Request Payload**: JSON formatında doğru mu?
   - **Response**: Hata mesajı var mı?

#### 4. Olası Hata Senaryoları

**Status 401 Unauthorized**:
- Kullanıcı admin değil
- Session süresi dolmuş
- `X-Username` header'ı gönderilmiyor

**Status 400 Bad Request**:
- LDAP URL boş
- Manager DN var ama şifre yok
- LDAP sunucusuna bağlanılamıyor
- Yanlış baseDn veya userSearchFilter

**Status 403 Forbidden**:
- Kullanıcı admin yetkisine sahip değil

**Request pending (dönüp duruyor)**:
- Backend çalışmıyor
- CORS hatası
- Network bağlantısı yok
- Frontend yanlış porta istek gönderiyor

### Bağlantı Hataları

#### 1. "LDAP: sunucuya ulaşılamadı"
- LDAP URL'sini kontrol edin (host, port)
- Firewall ayarlarını kontrol edin
- LDAP sunucusunun çalıştığından emin olun
- Test için `ldapsearch` komutunu kullanın:
  ```bash
  ldapsearch -x -H ldap://ldap.company.com:389 -b "dc=company,dc=com"
  ```

#### 2. "LDAP: manager hesabıyla bağlanılamadı"
- Manager DN'in doğru olduğundan emin olun
- Manager şifresini kontrol edin
- Manager hesabının aktif olduğundan emin olun

#### 3. "LDAP: kullanici bulunamadi"
- User Search Filter'ı kontrol edin
- Base DN'in doğru olduğundan emin olun
- Kullanıcı adının LDAP'daki kayıtlarla eşleştiğini kontrol edin

### Test Senaryoları

#### Senaryo 1: User DN Pattern ile Doğrudan Bağlantı
```java
// userDnPattern: uid={0},ou=people
// Bu durumda manager hesabı gerekmez
LdapSettings settings = new LdapSettings();
settings.setUrl("ldap://localhost:389");
settings.setBaseDn("dc=company,dc=com");
settings.setUserDnPattern("uid={0},ou=people");
// managerDn ve managerPassword boş bırakılabilir
```

#### Senaryo 2: User Search Filter ile Arama
```java
// userSearchFilter: (uid={0})
// Bu durumda manager hesabı gerekir
LdapSettings settings = new LdapSettings();
settings.setUrl("ldap://localhost:389");
settings.setManagerDn("cn=admin,dc=company,dc=com");
settings.setManagerPassword("admin123");
settings.setBaseDn("dc=company,dc=com");
settings.setUserSearchFilter("(uid={0})");
```

## Güvenlik Notları

1. **Şifreleme Anahtarları**: `secrets.properties` dosyasında saklanmalıdır.
2. **LDAPS Kullanımı**: Prod ortamda mutlaka LDAPS (ldaps://) kullanın.
3. **Manager Hesabı**: Minimum yetkilerle yapılandırılmalıdır.
4. **Loglama**: Hassas bilgiler (şifreler) loglara yazılmamalıdır.

## Test Komutları

### Backend Testi
```bash
cd backend
mvn test -Dtest=LdapAuthenticatorTest
```

### LDAP Bağlantı Testi
```bash
# OpenLDAP sunucusuna bağlantı testi
ldapsearch -x -H ldap://localhost:389 -D "cn=admin,dc=company,dc=com" -W -b "dc=company,dc=com"

# Active Directory bağlantı testi
ldapsearch -x -H ldap://ad.company.com:389 -D "CN=Administrator,CN=Users,DC=company,DC=com" -W -b "DC=company,DC=com"
```

## İyileştirme Önerileri

1. **Connection Pooling**: Çok sayıda istek için LDAP bağlantı havuzu eklenebilir.
2. **Cache**: Kullanıcı bilgileri için kısa süreli cache eklenebilir.
3. **Retry Mechanism**: Geçici bağlantı sorunları için tekrar deneme mekanizması eklenebilir.
4. **Health Check**: LDAP sunucusu durumunu kontrol eden health check endpoint'i eklenebilir.
5. **Logging**: Detaylı loglama (debug mode) eklenebilir.