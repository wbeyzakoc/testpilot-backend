# JSON Parse Hatası Düzeltme Raporu

## Problem
Model kararı alınamadı hatası alınıyordu:
```
Model geçerli JSON döndürmedi: Unexpected end-of-input: was expecting closing quote for a string value at [Source: REDACTED; line: 1, column: 2846] (through reference chain: com.testpilot.model.AgentAction["reasoning"])
```

## Kök Neden
LLM modeli, JSON yanıtında `reasoning` alanında geçersiz karakterler (tırnak işaretleri) döndürüyordu. Özellikle string değerlerin içinde kaçış dizesi (`\"`) olmadan tırnak işaretleri kullanılıyordu, bu da JSON parser'ın başarısız olmasına neden oluyordu.

Örnek hatalı JSON:
```json
{"reasoning": "Bu "butona" tıkla", "target": "...", ...}
```

Doğru JSON olmalıydı:
```json
{"reasoning": "Bu \"butona\" tıkla", "target": "...", ...}
```

## Uygulanan Çözümler

### 1. Sistem Promptu İyileştirmesi
`LlmAgent.java` içindeki `SYSTEM_PROMPT` güncellendi:
- LLM'ye JSON formatında cevap vermesi için daha net talimatlar eklendi
- Tırnak işareti kullanımına dair açık uyarılar eklendi
- Kaçış dizelerinin nasıl kullanılacağı örneklerle gösterildi

**Önemli değişiklikler:**
```
ÖNEMLİ - JSON FORMATI:
- SADECE geçerli JSON döndür, başka hiçbir metin ekleme
- reasoning ve target alanlarında KESİNLİKLE tırnak işareti (") kullanma
- Eğer bir alanda tırnak işareti kullanman gerekiyorsa, MUTLAKA kaçış dizesi ile yaz: \"
- ASLA şu şekilde yazma: "reasoning: "Bu butona tıkla"" - BU GEÇERSİZ JSON!
- DOĞRU: "reasoning: "Bu butona tıkla"" → "reasoning: "Bu \"butona\" tıkla""
```

### 2. JSON Temizleme Fonksiyonu
Yeni `cleanJsonResponse()` metodu eklendi:
- Geçersiz JSON'u otomatik olarak temizlemeye çalışır
- `reasoning`, `target` ve `text` alanlarındaki kaçışsız tırnak işaretlerini düzeltir
- Her string alanı tek tek tarar ve içindeki tırnak işaretlerini kaçışlı hale getirir

### 3. Retry Mekanizması
`decideNextAction()` metodu yeniden yapılandırıldı:
- 3 deneme hakkı ile retry loop eklendi
- Her başarısız denemeden sonra exponential backoff (1s, 2s, 3s) kullanılarak beklenir
- Tüm denemeler başarısız olursa detaylı hata mesajı döndürülür
- Ana mantık `makeLlmRequest()` metoduna taşındı

### 4. Gelişmiş Hata Ayıklama
JSON parse hatalarında daha fazla bilgi loglanıyor:
- Modelin ham cevabı
- Temizlenmiş JSON versiyonu
- Temizleme sonrası parse başarısı

## Değiştirilen Dosyalar
- `/backend/src/main/java/com/testpilot/agent/LlmAgent.java`

## Test Önerileri
1. Farklı senaryolarla test çalıştırın
2. Özellikle `reasoning` alanında tırnak işareti içerebilecek ifadeler kullanın
3. JSON parse hatalarının azaldığını kontrol edin
4. Retry mekanizmasının çalıştığını loglardan doğrulayın

## Beklenen Sonuçlar
- JSON parse hatalarında önemli azalma
- Model hatalı JSON döndüğünde otomatik temizleme ve retry
- Daha stabil ve güvenilir LLM entegrasyonu