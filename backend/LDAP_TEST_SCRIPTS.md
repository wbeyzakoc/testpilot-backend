# LDAP Ayarları Test Scripti

Bu script, LDAP ayarlarının kaydedilip kaydedilmediğini test etmek için kullanılır.

## Ön Koşullar

1. Backend'in çalışıyor olması gerekiyor:
```bash
cd backend
mvn spring-boot:run
```

2. Admin kullanıcı ile giriş yapılmış olmalı.

## Test Scripti

### 1. Mevcut LDAP Ayarlarını Getir

```bash
curl -X GET http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin"
```

**Beklenen Yanıt**:
```json
{
  "url": null,
  "baseDn": null,
  "managerDn": null,
  "managerPasswordSet": false,
  "userDnPattern": null,
  "userSearchFilter": null,
  "groupSearchBase": null,
  "groupSearchFilter": null,
  "passwordEncoderType": "bcrypt"
}
```

### 2. LDAP Ayarlarını Kaydet (Test Bağlantısı)

#### Senaryo A: User DN Pattern ile (Manager gerekmez)

```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://localhost:389",
    "baseDn": "dc=company,dc=com",
    "managerDn": "",
    "managerPassword": "",
    "userDnPattern": "uid={0},ou=people",
    "userSearchFilter": "",
    "groupSearchBase": "ou=groups,dc=company,dc=com",
    "groupSearchFilter": "memberUid={0}",
    "passwordEncoderType": "bcrypt"
  }'
```

**Beklenen Yanıt** (200 OK):
```json
{
  "url": "ldap://localhost:389",
  "baseDn": "dc=company,dc=com",
  "managerDn": null,
  "managerPasswordSet": false,
  "userDnPattern": "uid={0},ou=people",
  "userSearchFilter": null,
  "groupSearchBase": "ou=groups,dc=company,dc=com",
  "groupSearchFilter": "memberUid={0}",
  "passwordEncoderType": "bcrypt"
}
```

#### Senaryo B: User Search Filter ile (Manager gerekli)

```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://localhost:389",
    "baseDn": "dc=company,dc=com",
    "managerDn": "cn=admin,dc=company,dc=com",
    "managerPassword": "admin123",
    "userDnPattern": "",
    "userSearchFilter": "(uid={0})",
    "groupSearchBase": "ou=groups,dc=company,dc=com",
    "groupSearchFilter": "(memberUid={0})",
    "passwordEncoderType": "bcrypt"
  }'
```

**Beklenen Yanıt** (200 OK - Eğer LDAP sunucusu çalışıyorsa):
```json
{
  "url": "ldap://localhost:389",
  "baseDn": "dc=company,dc=com",
  "managerDn": "cn=admin,dc=company,dc=com",
  "managerPasswordSet": true,
  "userDnPattern": null,
  "userSearchFilter": "(uid={0})",
  "groupSearchBase": "ou=groups,dc=company,dc=com",
  "groupSearchFilter": "(memberUid={0})",
  "passwordEncoderType": "bcrypt"
}
```

### 3. Hata Senaryoları Testi

#### Hata 1: Manager DN var ama şifre yok

```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://localhost:389",
    "baseDn": "dc=company,dc=com",
    "managerDn": "cn=admin,dc=company,dc=com",
    "managerPassword": "",
    "userDnPattern": "",
    "userSearchFilter": "(uid={0})",
    "groupSearchBase": "",
    "groupSearchFilter": "",
    "passwordEncoderType": "bcrypt"
  }'
```

**Beklenen Yanıt** (400 Bad Request):
```
Manager DN girildi ama manager şifresi yok. Lütfen manager şifresini girin veya Manager DN alanını boş bırakın.
```

#### Hata 2: LDAP URL boş

```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -d '{
    "url": "",
    "baseDn": "dc=company,dc=com",
    "managerDn": "",
    "managerPassword": "",
    "userDnPattern": "uid={0},ou=people",
    "userSearchFilter": "",
    "groupSearchBase": "",
    "groupSearchFilter": "",
    "passwordEncoderType": "bcrypt"
  }'
```

**Beklenen Yanıt** (400 Bad Request):
```
LDAP URL boş olamaz.
```

#### Hata 3: Yetkisiz erişim (X-Username yok)

```bash
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -d '{
    "url": "ldap://localhost:389",
    "baseDn": "dc=company,dc=com",
    "managerDn": "",
    "managerPassword": "",
    "userDnPattern": "uid={0},ou=people",
    "userSearchFilter": "",
    "groupSearchBase": "",
    "groupSearchFilter": "",
    "passwordEncoderType": "bcrypt"
  }'
```

**Beklenen Yanıt** (401 Unauthorized veya 403 Forbidden):
```
Admin yetkisi gerekli.
```

### 4. Mevcut Şifreyi Koruma Testi

Mevcut bir şifre varsa ve yeni şifre gönderilmezse, mevcut şifre korunmalı:

```bash
# 1. Önce şifre ile kaydet
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://localhost:389",
    "managerDn": "cn=admin,dc=company,dc=com",
    "managerPassword": "oldpassword123",
    "userSearchFilter": "(uid={0})"
  }'

# 2. Sonra şifre göndermeden sadece URL güncelle
curl -X PUT http://localhost:4000/settings/ldap \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -d '{
    "url": "ldap://newhost:389",
    "managerDn": "cn=admin,dc=company,dc=com",
    "managerPassword": "",
    "userSearchFilter": "(uid={0})"
  }'
```

**Beklenen**: İkinci istekte şifre boş gönderilse bile, eski şifre ("oldpassword123") korunmalı ve test başarılı olmalı.

## PowerShell Script (Windows)

```powershell
# LDAP Ayarlarını Kaydet
$ldapSettings = @{
    url = "ldap://localhost:389"
    baseDn = "dc=company,dc=com"
    managerDn = "cn=admin,dc=company,dc=com"
    managerPassword = "admin123"
    userDnPattern = ""
    userSearchFilter = "(uid={0})"
    groupSearchBase = "ou=groups,dc=company,dc=com"
    groupSearchFilter = "(memberUid={0})"
    passwordEncoderType = "bcrypt"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:4000/settings/ldap" `
  -Method PUT `
  -ContentType "application/json" `
  -Headers @{"X-Username"="admin"} `
  -Body $ldapSettings
```

## Bash Script (macOS/Linux)

```bash
#!/bin/bash

# LDAP Ayarlarını Kaydet Scripti

API_URL="http://localhost:4000"
USERNAME="admin"

# LDAP ayarları
LDAP_JSON='{
  "url": "ldap://localhost:389",
  "baseDn": "dc=company,dc=com",
  "managerDn": "cn=admin,dc=company,dc=com",
  "managerPassword": "admin123",
  "userDnPattern": "",
  "userSearchFilter": "(uid={0})",
  "groupSearchBase": "ou=groups,dc=company,dc=com",
  "groupSearchFilter": "(memberUid={0})",
  "passwordEncoderType": "bcrypt"
}'

# İstek gönder
echo "LDAP ayarları kaydediliyor..."
response=$(curl -s -w "\n%{http_code}" -X PUT "${API_URL}/settings/ldap" \
  -H "Content-Type: application/json" \
  -H "X-Username: ${USERNAME}" \
  -d "${LDAP_JSON}")

# Yanıtı ayır
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

echo "HTTP Status: ${http_code}"
echo "Response: ${body}"

if [ "$http_code" = "200" ]; then
  echo "✓ LDAP ayarları başarıyla kaydedildi!"
  exit 0
else
  echo "✗ LDAP ayarları kaydedilemedi!"
  exit 1
fi
```

## Veritabanı Kontrolü

LDAP ayarlarının veritabanına kaydedilip kaydedilmediğini kontrol etmek için:

```sql
-- Oracle Database
SELECT * FROM MOBILE_LDAP_SETTINGS WHERE ID = 1;

-- Sonuç:
-- ID | URL | BASE_DN | MANAGER_DN | MANAGER_PASSWORD_ENCRYPTED | ...
-- 1  | ldap://localhost:389 | dc=company,dc=com | cn=admin,dc=company,dc=com | [encrypted] | ...
```

## Log Kontrolü

Backend loglarında şu mesajları arayın:

**Başarılı Kayıt**:
```
LdapSettingsController.update - LDAP ayarları başarıyla kaydedildi
```

**Başarısız Test Bağlantı**:
```
LdapAuthenticator.testConnection - LDAP bağlantı hatası: [detay]
LdapSettingsController.update - LDAP ayarları kaydedilemedi: [hata mesajı]
```

**Yetki Hatası**:
```
CurrentUserResolver.requireAdmin - Admin yetkisi gerekli
```