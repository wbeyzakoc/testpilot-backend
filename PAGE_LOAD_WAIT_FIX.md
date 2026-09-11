# Sayfa Yükleme ve İlk Adım Bekleme Düzeltmesi

## Problem

```
Uygulama açıldı ve hemen tıklama yaptı. Bu nedenle ürünü bulamadı ve sonrasında scroll yapmaya başladı.

Tıkla: Product Image (1)
Hedef ürün 'Sauce Labs Backpack (yellow)' bulunamadı. Ürün ismi text alanında 'Sauce Labs Backpack' içeriyor ancak renk bilgisi yok.

Tıkla: Add to cart
Hedef ürün 'Sauce Labs Backpack (yellow)' bulunamadı. Ancak 'Sauce Labs Backpack' metni [12] elementinde var.
```

**Sorun**: Uygulama açıldıktan sonra model hemen tıklama yapıyor, sayfa henüz yüklenmemiş. Ürün listesi görünmüyor.

## Kök Neden

1. **Yetersiz Bekleme Süresi**: Uygulama açıldıktan sonra sadece 2.5s + 2s = 4.5s bekleniyordu
2. **İlk Adım Hızlı**: İlk adımda sadece 1.5s ek bekleme yapılıyordu
3. **Model Prompt'u Zayıf**: "Step 1 = ALWAYS action=wait" uyarısı yeterince güçlü değildi
4. **Liste Yükleme**: Ürün listesi ekranlarında ürünlerin yüklenmesi daha uzun sürüyor

## Uygulanan Çözümler

### 1. Toplam Bekleme Süresi Artırıldı

**Önceki Akış** (toplam ~6s):
```
1. startSession() → 2.5s
2. Ek bekleme → 2.0s
3. İlk adım → 1.5s
   TOPLAM: 6.0 saniye
```

**Yeni Akış** (toplam ~9.5s):
```
1. startSession() → 2.5s
2. Ek bekleme → 3.0s (0.5s artırıldı)
3. İlk adım → 2.0s (0.5s artırıldı)
   TOPLAM: 7.5 saniye
```

**Kod Değişikliği** (`RunController.java`):
```java
// ÖNCEKİ:
Thread.sleep(2500);
Thread.sleep(2000); // Ek bekleme

// YENİ:
Thread.sleep(2500);
Thread.sleep(3000); // Ek bekleme artırıldı

// İlk adımda:
if (isFirstStep) {
    System.out.println("[RUN] İlk adım: Sayfa stabilitesi için 2 saniye ek bekleme");
    Thread.sleep(2000); // 1500ms → 2000ms
    isFirstStep = false;
}
```

### 2. SYSTEM_PROMPT Güçlendirildi

**Önceki Prompt**:
```
- wait: For page loading (step 1 only)
6. PAGE LOAD: Step 1 = ALWAYS action=wait. System auto-scrolls if element not visible.
```

**Yeni Prompt**:
```
- wait: CRITICAL for step 1 - page needs 3-5 seconds to fully load. Use wait=3 if step 1.
6. PAGE LOAD: Step 1 = ALWAYS action=wait (2-3 seconds) to let page load. System auto-scrolls if element not visible.
```

**Etki**: Model artık "CRITICAL" ve "3-5 seconds" uyarılarını görüyor, ilk adımda `wait` action'ı kullanma ihtimali artıyor.

### 3. Logging İyileştirildi

İlk adımda bekleme loglanıyor:
```java
System.out.println("[RUN] İlk adım: Sayfa stabilitesi için 2 saniye ek bekleme");
```

Uygulama açılışında:
```java
System.out.println("[RUN] Uygulama açıldı, sayfa yükleniyor... (3 saniye bekleniyor)");
Thread.sleep(3000);
System.out.println("[RUN] Sayfa yüklendi, test başlıyor");
```

## Beklenen Sonuçlar

✅ **Daha uzun bekleme**: Toplam ~7.5 saniye sayfa yüklenmesi için  
✅ **Model uyarısı**: İlk adımda `wait` action'ı kullanma ihtimali artıyor  
✅ **Ürün listesi**: Ürünler tamamen yüklenmiş durumda  
✅ **Doğru tıklama**: Ürün bulunabiliyor ve doğru elemente tıklanıyor  

## Test Senaryosu

**Hedef**: "Sauce Labs Backpack (yellow) ürünü bul ve sepete ekle"

**Beklenen Akış**:
1. Uygulama açılır → 2.5s bekleme
2. Sayfa yüklenir → 3.0s bekleme
3. İlk adım → Model `wait` action'ı kullanır veya 2s ek bekleme yapılır
4. Ürün listesi görünür → "Sauce Labs Backpack (yellow)" bulunur
5. Ürüne tıklanır → Product Image (2) veya Product Title (2)
6. "Add to Cart" butonuna tıklanır
7. Test tamamlanır

**Log Çıktısı**:
```
[RUN] Uygulama açıldı, sayfa yükleniyor... (3 saniye bekleniyor)
[RUN] İlk adım: Sayfa stabilitesi için 2 saniye ek bekleme
[locator] XPath ile bulundu: //*[@label='Sauce Labs Backpack (yellow)']
[TARGET] Çözüldü: x=540, y=1200
```

## Ek İyileştirmeler (Gerekirse)

Eğer sorun devam ederse:

### 1. Daha Uzun Bekleme
```java
// 3.0s → 5.0s
Thread.sleep(5000);

// İlk adımda 2.0s → 3.0s
Thread.sleep(3000);
```

### 2. Sayfa Yüklendi Kontrolü
```java
// Belirli bir element görünene kadar bekle
waitForElementVisible(runId, "some-unique-id", timeoutMs);
```

### 3. Model'e Ekstra İpucu
Prompt'a ekle:
```
STEP 1 RULE: If you see a product list or shopping screen, NEVER tap immediately.
Wait for page to fully load (3-5 seconds). Use action=wait with x=0, y=0.
```

### 4. Auto-Scroll Öncesi Bekleme
Backend otomatik kaydırma yapmadan önce:
```java
if (autoScrollIfNeeded(...)) {
    Thread.sleep(1000); // Kaydırma sonrası elementlerin yüklenmesi için
}
```

## Performans Etkisi

| Metrik | Önceki | Yeni | Fark |
|--------|--------|------|------|
| Toplam bekleme | ~6s | ~7.5s | +1.5s |
| İlk adım gecikmesi | ~1.5s | ~2.0s | +0.5s |
| Sayfa yükleme | ~4.5s | ~5.5s | +1.0s |

**Artı**: Daha stabil testler, daha az hata  
**Eksi**: Test başına ~1.5s daha uzun  

## İlgili Dosyalar

- [`RunController.java`](/Users/vakifbank/Documents/GitHub/Beyza/testpilot-backend/backend/src/main/java/com/testpilot/controller/RunController.java) - Bekleme süreleri
- [`LlmAgent.java`](/Users/vakifbank/Documents/GitHub/Beyza/testpilot-backend/backend/src/main/java/com/testpilot/agent/LlmAgent.java) - SYSTEM_PROMPT