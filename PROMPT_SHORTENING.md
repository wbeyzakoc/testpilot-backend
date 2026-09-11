# Prompt Kısaltma Raporu

## Özet
SYSTEM_PROMPT, yapı bozulmadan %70'den fazla kısaltıldı ve İngilizce'ye çevrildi.

## Değişiklikler

### Önceki Durum
- **Dil**: Türkçe
- **Uzunluk**: ~290 satır
- **Yapı**: Dağınık, tekrarlayan açıklamalar, çok fazla örnek

### Yeni Durum
- **Dil**: İngilizce (daha kısa ve net)
- **Uzunluk**: ~65 satır (%77 azalma)
- **Yapı**: Organize, madde işaretleri, net kurallar

## Korunan Tüm Önemli Kurallar

✅ **JSON Formatı**: Sadece JSON döndür, başka metin yok  
✅ **Alan Kuralları**: reasoning, target, elementId, action, x, y, text, direction  
✅ **Action Türleri**: tap, type, swipe, wait, done, fail - her biri için kurallar  
✅ **Tırnak İşareti Uyarısı**: JSON parse hatası için kritik uyarı korundu  
✅ **Geçmiş Kontrolü**: Aynı elementi tekrar seçmeme kuralı  
✅ **Popup Önceliği**: Popup'ları önce kapat  
✅ **Ürün Arama**: label, text, content-desc alanlarını kontrol et  
✅ **Swipe Stratejisi**: Element bulunamazsa fail verme, swipe yap  
✅ **Sayfa Yükleme**: Adım 1 = always wait  
✅ **Direction Kuralları**: up/down doğru kullanımı  
✅ **Gereksiz Navigasyon**: Sadece gerekirse gezinme adımı  
✅ **Decision Algorithm**: 4 adımlı karar algoritması  

## Kısaltma Teknikleri

1. **Dil Değişimi**: Türkçe → İngilizce (daha kısa ifadeler)
2. **Tekrarların Kaldırılması**: Aynı kuralın farklı yerlerde tekrarlanması kaldırıldı
3. **Detaylı Örneklerin Sadeleştirilmesi**: 4 uzun örnek → 1 kısa örnek
4. **Madde İşaretleri**: Uzun paragraflar → okunabilir liste
5. **Gereksiz Açıklamaların Silinmesi**: Backend'in zaten yaptığı işlemlerin açıklamaları kaldırıldı

## Beklenen Faydalar

- **Daha Hızlı API Yanıtları**: Daha kısa prompt = daha hızlı işlem
- **Daha Az Token Maliyeti**: %77 daha az prompt token
- **Daha Net Anlama**: İngilizce ve organize yapı, modelin daha iyi anlamasını sağlar
- **Daha Az Hata**: Net kurallar, daha az belirsizlik

## Test Önerileri

1. Farklı senaryolarla test çalıştırın
2. Modelin kuralları doğru takip ettiğini kontrol edin
3. JSON parse hatalarının devam edip etmediğini izleyin
4. Response sürelerindeki iyileşmeyi ölçün

## Dosya
- `/backend/src/main/java/com/testpilot/agent/LlmAgent.java` - SYSTEM_PROMPT constant