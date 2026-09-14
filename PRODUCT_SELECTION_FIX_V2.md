# Ürün Seçim Sorunu Düzeltmesi - İki Senaryo Ayrımı

## 📋 Sorun Tanımı

**Senaryo A (Tam Ürün Adı):** "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bul. bulunca tıkla ve testi bitir."

**Senaryo B (Kelime İçerme):** "uygulamayı aç. içerisinde yellow yazan ürünü bul. bulunca tıkla ve testi bitir."

**Sorun:** 
- Senaryo A'da "Sauce Labs Backpack (yellow)" tam ürün adı belirtilmiş olmasına rağmen, **rastgele başka bir ürün** (mavi, kırmızı, yeşil vb.) seçiliyordu.
- Senaryo B'de "içerisinde yellow yazan ürün" denildiğinde, **sadece belirli bir ürün** aranıyor ve diğer yellow ürünler gözden kaçırıyordu.

## 🔍 Kök Neden Analizi

Kod incelemesinde aşağıdaki bulgulara ulaşıldı:

### Mevcut Durum
1. ✅ **Altyapı doğru çalışıyor:** `findMatchingTarget()` metodu deterministik string eşleşmesiyle doğru ürünü bulabiliyor
2. ✅ **Koruma mekanizması var:** `RunController`'da yanlış ürün seçimi engelleme mevcut
3. ❌ **Sistem prompt yetersiz:** LLM modeline iki farklı senaryo (TAM AD vs KELİME İÇERME) arasındaki ayrım net olarak anlatılmamış

### Sorunun Kaynağı
LLM agent'ın sistem prompt'u:
- Tam ürün adı ile kelime içerme araması arasındaki FARKI açıklamıyor
- "Sauce Labs Backpack (yellow)" denildiğinde SADECE o ürünün seçilmesi gerektiğini vurgulamıyor
- "yellow yazan ürün" denildiğinde ise HER yellow ürünün seçilebileceğini belirtmiyor

## ✅ Uygulanan Çözümler

### 1. İki Senaryo Ayrımı Eklendi (`LlmAgent.java`)

**EKLENEN ÖZELLİK: SENARYO TANIMA VE AYRIMI**

#### 🎯 Senaryo A: TAM ÜRÜN ADI VERİLMİŞ
```
Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
→ SADECE TAM ADINA EŞLEŞEN ÜRÜNÜ SEÇ!
→ [product: Sauce Labs Backpack (yellow)] → DOĞRU ✓
→ [product: Sauce Labs Backpack (blue)] → YANLIŞ! (renk farklı)
→ [product: onesie (yellow)] → YANLIŞ! (ürün adı farklı!)
→ İçinde "yellow" kelimesi geçen HER ÜRÜN DEĞİL, SADECE TAM ADI EŞLEŞEN ÜRÜN!
```

#### 🎯 Senaryo B: ÖZELLİK/KELİME İLE ARAMA
```
Goal: "içerisinde yellow yazan ürünü seç"
→ İÇERİSİNDE O KELİME GEÇEN HER ÜRÜNÜ SEÇEBİLİRSİN!
→ [product: onesie (yellow)] → DOĞRU ✓
→ [product: Sauce Labs Backpack (yellow)] → DOĞRU ✓
→ [product: yellow t-shirt] → DOĞRU ✓
→ [product: blue backpack] → YANLIŞ! (yellow içermiyor)
```

**KRİTİK AYIRIM:**
- "Sauce Labs Backpack (yellow) ürününü seç" → SADECE O EXACT ürün (blue/red YANLIŞ!)
- "yellow yazan ürünü seç" → yellow içeren HER ÜRÜN (onesie, t-shirt, backpack HEPSİ DOĞRU!)

### 2. Adım Adım Ürün Arama Süreci Eklendi

**YENİ BÖLÜM: Hedef Analizi ve Eşleştirme Stratejisi**

```
1. HEDERİ ANALİZ ET - İKİ FARKLI SENARYO VAR:

2. [product: X] ANNOTASYONLARINI MUTLAKA OKU:
   - Her annotasyonu oku - her biri FARKLI ürün adı!

3. Hedefe göre EŞLEŞTİRME STRATEJİSİ belirle:
   
   A) TAM AD EŞLEŞTİRME:
      - Goal'daki ürün adını TAM OLARAK ara
      - Sadece TAM eşleşen ürüne tap!
      
   B) KELİME İÇERME EŞLEŞTİRME:
      - Goal'da "içerisinde ... yazan" varsa:
      - O kelimeyi içeren HER ÜRÜNÜ seç!

4. X XML'de VARSA → tap et
5. X XML'de YOKSA → swipe ile ara
```

### 3. Örnek Akışlar Eklendi

**Örnek 1 - TAM AD EŞLEŞTİRME:**
```
- Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
- Scroll 1 → Tüm elementleri tara: 
  [1] [product: Sauce Labs Backpack (blue)] → TAM AD YOK → Devam
  [2] [product: Sauce Labs Backpack (red)] → TAM AD YOK → Devam
  [3] [product: Sauce Labs Backpack (green)] → TAM AD YOK → Scroll again
- Scroll 2 → Tüm elementleri tara:
  [4] [product: Sauce Labs Backpack (black)] → TAM AD YOK → Devam
  [5] [product: Sauce Labs Backpack (yellow)] → TAM AD EŞLEŞTİ! → TAP ✓
```

**Örnek 2 - KELİME İÇERME EŞLEŞTİRME:**
```
- Goal: "içerisinde yellow yazan ürünü seç"
- Scroll 1 → Tüm elementleri tara:
  [1] [product: Sauce Labs Backpack (blue)] → yellow YOK → Devam
  [2] [product: onesie (yellow)] → yellow VAR! → TAP ✓ (İLK UYGUN ÜRÜN)
```

### 4. Tap Öncesi Kontrol Listesi Güncellendi

**YENİ 3 ADIMLI KONTROL SÜRECİ:**

```
ADIM 1: Goal'ı analiz et - Hangi senaryo?
- "[Ürün Adı] ürününü seç/bul" → SENARYO A (TAM AD)
- "içerisinde ... yazan/olan ürün" → SENARYO B (KELİME İÇERME)

ADIM 2: Senaryoya göre eşleştirme yap:

SENARYO A:
- Goal: "Sauce Labs Backpack (yellow) ürününü seç"
- Element: [product: Sauce Labs Backpack (yellow)]
  ✓ TAM AD var mı? → EVET → TAP ✓
- Element: [product: Sauce Labs Backpack (blue)]
  ✗ TAM AD var mı? → HAYIR → SWİPE
- Element: [product: onesie (yellow)]
  ✗ TAM AD var mı? → HAYIR → SWİPE

SENARYO B:
- Goal: "içerisinde yellow yazan ürünü seç"
- Element: [product: onesie (yellow)]
  ✓ "yellow" var mı? → EVET → TAP ✓
- Element: [product: blue backpack]
  ✗ "yellow" var mı? → HAYIR → SWİPE

ADIM 3: Son kontrol (renk/boyut/model)
```

### 5. Detaylı Son Kontrol Örnekleri Eklendi

**Senaryo A Örneği:**
```
Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
Seçilen element: [5] "Product Image [product: Sauce Labs Backpack (blue)]"

Kontrol:
✓ Element XML'de var mı? → EVET
✓ Tıklanabilir mi? → EVET
✗ TAM AD "Sauce Labs Backpack (yellow)" var mı? → HAYIR

SONUÇ: ASLA tap yapma! action=swipe döndür.
```

**Senaryo B Örneği:**
```
Goal: "içerisinde yellow yazan ürünü seç"
Seçilen element: [6] "Product Image [product: onesie (yellow)]"

Kontrol:
✓ Element XML'de var mı? → EVET
✓ Tıklanabilir mi? → EVET
✓ "yellow" kelimesi var mı? → EVET

SONUÇ: action=tap döndür! ✓
```

## 🧪 Test Senaryoları

### Senaryo A - Tam Ürün Adı
```
Test: "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bul. bulunca tıkla ve testi bitir."

Beklenen Akış:
1. Uygulama açılır
2. Ürün listesi yüklenir
3. Sistem ekrandaki tüm ürünleri tarar
4. HER ÜRÜNÜN [product: X] annotasyonunu kontrol eder
5. "Sauce Labs Backpack (yellow)" TAM ADINI arar
6. Eğer bu TAM ürün ekrandaysa → HEMEN tap eder
7. Eğer yoksa → ekranı kaydırır ve YENİDEN TAM ADI arar
8. SADECE "Sauce Labs Backpack (yellow)" bulunduğunda tap eder
9. Diğer ürünler (blue/red/green/onesie yellow) YANLIŞ kabul edilir

❌ ÖNCEKI: Rastgele bir ürün seçiliyordu
✅ YENİ: SADECE "Sauce Labs Backpack (yellow)" seçilecek
```

### Senaryo B - Kelime İçerme
```
Test: "uygulamayı aç. içerisinde yellow yazan ürünü bul. bulunca tıkla ve testi bitir."

Beklenen Akış:
1. Uygulama açılır
2. Ürün listesi yüklenir
3. Sistem ekrandaki tüm ürünleri tarar
4. HER ÜRÜNÜN annotasyonunda "yellow" kelimesini arar
5. İlk "yellow" içeren ürün bulunduğunda → tap eder
6. "yellow onesie", "yellow t-shirt", "Sauce Labs Backpack (yellow)" HEPSİ DOĞRU

❌ ÖNCEKI: Sadece belirli bir ürün aranıyordu
✅ YENİ: yellow içeren İLK UYGUN ürün seçilecek
```

## 📊 Teknik Detaylar

### Değiştirilen Dosyalar
- `/backend/src/main/java/com/testpilot/agent/LlmAgent.java`

### Değişiklikler
1. **Hedef Analiz Bölümü** (~25 satır eklendi)
   - İki senaryo ayrımı (TAM AD vs KELİME İÇERME)
   - Senaryo tanıma kuralları

2. **Eşleştirme Stratejisi** (~35 satır eklendi)
   - Senaryo A: Tam ad eşleştirme kuralları
   - Senaryo B: Kelime içerme eşleştirme kuralları
   - Örnek akışlar

3. **Renk/Varyant/Model Ayırımı** (~20 satır eklendi)
   - Her iki senaryo için ayrı kurallar
   - Yanlış mantık vs doğru mantık örnekleri

4. **Son Kontrol Bölümü** (~40 satır eklendi)
   - 3 adımlı kontrol süreci
   - Her iki senaryo için detaylı örnekler

### Derleme Durumu
```
✅ BUILD SUCCESS
✅ 69 source files compiled successfully
✅ No syntax errors
```

## 🎯 Beklenen Sonuçlar

### Senaryo A için:
1. ✅ LLM agent artık "Sauce Labs Backpack (yellow)" denildiğinde SADECE o ürünü arayacak
2. ✅ "blue/red/green" varyantlarını YANLIŞ kabul edecek
3. ✅ "onesie (yellow)" gibi FARKLI ÜRÜNLERİ de YANLIŞ kabul edecek
4. ✅ Bulunana kadar kaydırmaya devam edecek

### Senaryo B için:
1. ✅ LLM agent artık "yellow yazan ürün" denildiğinde HER yellow ürünü kabul edecek
2. ✅ İlk bulunan yellow ürüne tap edecek
3. ✅ "blue/red/green" ürünleri atlayacak

## 🔄 Sonraki Adımlar

1. Projeyi yeniden başlatın
2. Her iki senaryoyu test edin:
   - "Sauce Labs Backpack (yellow) ürününü bul" → SADECE o ürün seçilmeli
   - "içerisinde yellow yazan ürünü seç" → İlk yellow ürün seçilmeli
3. Logları izleyin - senaryo analizi ve eşleştirme adımlarını göreceksiniz

## 📝 Önemli Not

Bu düzeltme, **iki farklı senaryo arasındaki kritik ayrımı** netleştirir:

- **"Sauce Labs Backpack (yellow) ürününü seç"** → TAM AD eşleşmesi gerekir
  - Sadece o EXACT ürün kabul edilir
  - Renk/varyant/ürün adı FARKLI ise → YANLIŞ
  
- **"yellow yazan ürünü seç"** → KELİME İÇERME eşleşmesi yeterlidir
  - yellow içeren HER ÜRÜN kabul edilir
  - Ürün adı farklı olsa bile (onesie, t-shirt, backpack) → DOĞRU

LLM agent artık bu iki senaryoyu AYIRT edebilecek şekilde eğitilmiştir.