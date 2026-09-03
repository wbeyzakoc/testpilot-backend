# Password Encoder Type - "NO" Seçeneği Eklendi

## Değişiklik Özeti

LDAP ayarları sayfasındaki "Password Encoder Type" dropdown'ına ilk seçenek olarak **"NO"** (şifreleme yok) eklendi.

## Backend Değişiklikleri

### 1. Varsayılan Değer Güncellendi

**Dosya**: `backend/src/main/java/com/testpilot/model/LdapSettings.java`

**Önceki**:
```java
private String passwordEncoderType = "bcrypt";
```

**Yeni**:
```java
private String passwordEncoderType = "NO";
```

### 2. Desteklenen Değerler

Artık şu değerler desteklenmektedir:
- **NO** - Şifreleme yok (varsayılan)
- **bcrypt** - BCrypt şifreleme
- **sha256** - SHA-256 hash (gelecek versiyon)
- **sha512** - SHA-512 hash (gelecek versiyon)

## Frontend Değişiklikleri (Yapılması Gerekenler)

Frontend dosyaları bu workspace'de bulunmuyor. Frontend projesinde şu değişiklikleri yapın:

### 1. Password Encoder Type Seçeneklerini Güncelle

**Dosya**: `frontend/src/pages/settings/ldap.tsx` (veya benzeri dosya)

**Önceki Kod**:
```typescript
const passwordEncoderOptions = [
  { value: 'bcrypt', label: 'BCrypt' },
  { value: 'sha256', label: 'SHA-256' },
  { value: 'sha512', label: 'SHA-512' },
];

// veya
const passwordEncoderOptions = [
  { value: 'bcrypt', label: 'BCrypt (Önerilen)' },
];
```

**Yeni Kod**:
```typescript
const passwordEncoderOptions = [
  { value: 'NO', label: 'No (Şifreleme Yok)' },
  { value: 'bcrypt', label: 'BCrypt (Önerilen)' },
  { value: 'sha256', label: 'SHA-256' },
  { value: 'sha512', label: 'SHA-512' },
];
```

### 2. Select Component'i Güncelle

```typescript
<Select
  value={ldapSettings.passwordEncoderType}
  onChange={(value) => setLdapSettings({ ...ldapSettings, passwordEncoderType: value })}
  options={passwordEncoderOptions}
  label="Password Encoder Type"
/>
```

### 3. Varsayılan Değer Kontrolü

Eğer mevcut bir ayar yoksa veya `passwordEncoderType` boşsa, varsayılan olarak "NO" kullan:

```typescript
const defaultLdapSettings: LdapSettings = {
  url: '',
  baseDn: '',
  managerDn: '',
  managerPasswordSet: false,
  userDnPattern: '',
  userSearchFilter: '',
  groupSearchBase: '',
  groupSearchFilter: '',
  passwordEncoderType: 'NO', // Varsayılan değer
};
```

## Kullanım Senaryoları

### "NO" Ne Zaman Kullanılmalı?

1. **Test Ortamları**: Hızlı test yapmak için
2. **Güvenli LDAP Sunucuları**: LDAP sunucusu zaten TLS/SSL ile şifreliyse
3. **Dış LDAP Servisleri**: Üçüncü parti LDAP servisleri kullanırken
4. **Geliştirme Ortamları**: Lokal geliştirme sırasında

### "bcrypt" Ne Zaman Kullanılmalı?

1. **Prod Ortamları**: Üretim ortamlarında önerilen
2. **Yüksek Güvenlik**: Yüksek güvenlik gerektiren ortamlar
3. **Sertifikasyon Gerektiren Ortamlar**: PCI-DSS, HIPAA gibi

## Önemli Notlar

### LOCAL Kullanıcılar İçin

"Password Encoder Type" ayarı **sadece LDAP kullanıcıları** için geçerlidir. LOCAL kullanıcılar (admin panelinden eklenenler) her zaman BCrypt ile şifrelenir.

### LDAP Kullanıcıları İçin

LDAP kullanıcılarının şifreleri **hiç saklanmaz**. Her girişte LDAP sunucusuna karşı doğrulanır. Bu ayar sadece belgelendirme amaçlıdır ve gelecekteki özellikler için hazırlıktır.

### Geriye Dönük Uyumluluk

Mevcut "bcrypt" ayarları etkilenmez. Sadece yeni oluşturulan kayıtlar "NO" ile başlar.

## Test Senaryoları

### Test 1: Varsayılan Değer Kontrolü

```bash
# Yeni LDAP ayarı oluşturulduğunda
curl -X GET http://localhost:4000/settings/ldap \
  -H "X-Username: admin"

# Beklenen: passwordEncoderType = "NO"
```

### Test 2: Güncelleme

```bash
# bcrypt ile güncelleme
curl -X PUT http://localhost:4000/settings/ldap \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://localhost:389",
    "passwordEncoderType": "bcrypt"
  }'

# Beklenen: passwordEncoderType = "bcrypt"
```

## Gelecek Geliştirmeler

1. **Dinamik Şifreleme**: LOCAL kullanıcılar için de password encoder type seçeneği
2. **Özel Encoder**: Kendi şifreleme algoritmanızı tanımlama
3. **Şifreleme Testi**: Seçilen encoder'ın test edilmesi

## Sorun Giderme

### "NO" seçeneği görünmüyor
- Frontend kodunu güncellediğinizden emin olun
- Browser cache'i temizleyin
- Frontend'i yeniden build edin

### Varsayılan değer "bcrypt" olarak kalıyor
- Backend'in yeniden derlendiğinden emin olun
- Veritabanındaki mevcut kayıtları kontrol edin
- `UPDATE MOBILE_LDAP_SETTINGS SET PASSWORD_ENCODER_TYPE = 'NO' WHERE ID = 1;`

## Sonuç

✅ Backend'de varsayılan değer "NO" olarak güncellendi
⏳ Frontend'de dropdown seçenekleri güncellenmeli
✅ Geriye dönük uyumluluk korundu
✅ Gelecek geliştirmeler için altyapı hazır

Frontend dosyalarını güncelledikten sonra LDAP ayarları sayfasında "Password Encoder Type" dropdown'unun ilk seçeneği **"NO (Şifreleme Yok)"** olacak.