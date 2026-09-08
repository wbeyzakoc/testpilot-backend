# TestPilot AI — Çalıştırma Kılavuzu

Kısa pratik bilgilendirme: backend, frontend ve Appium/Device Farm nasıl ayağa kaldırılır. Selenium Grid ile paralel koşum (birden fazla Appium instance + Grid hub/node) için `ai-auto-testing-backend/README.md`'ye bakın — burası günlük kullandığımız Device Farm akışını anlatıyor.

## Backend

- Klasör: `ai-auto-testing-backend/backend`
- IntelliJ'den `TestPilotApplication`'ı çalıştır (en pratik yol), ya da terminalden:
  ```bash
  cd ai-auto-testing-backend/backend
  mvn spring-boot:run
  ```
- Varsayılan port: **4000** (`application.properties` → `server.port`)
- Veritabanı: Oracle (`jdbc:oracle:thin:@localhost:1521/FREEPDB1`, kullanıcı `testpilotapp`)
- `spring.jpa.hibernate.ddl-auto`: yeni tablo/kolon eklendiğinde (migration) geçici olarak `update` yapılır, Hibernate otomatik oluşturduktan sonra tekrar `none`/`validate`'e döndürülür — DB'yi olduğu gibi korumak için varsayılan hep `none`/`validate` olmalı.

## Frontend

- Klasör: `cozy-creatorr`
- ```bash
  cd cozy-creatorr
  npm run dev
  ```
- Terminalde basılan adresten açılır (Vite dev server). Backend'e `http://localhost:4000` üzerinden bağlanır (bkz. `AGENT_API_URL` sabiti, route dosyalarının başında).

## Appium / Device Farm

Kullandığımız komut:

```bash
appium server -ka 800 --use-plugins=device-farm -pa /wd/hub --plugin-device-farm-platform=both
```

Ne işe yarıyor:

- `-ka 800` — keep-alive timeout (ms): bağlantının boşta ne kadar açık kalacağı.
- `--use-plugins=device-farm` — Device Farm eklentisini aktif eder; bağlı emulator/simulator/gerçek cihazları otomatik keşfeder ve bir dashboard sunar.
- `-pa /wd/hub` — Appium'un WebDriver taban yolunu `/wd/hub` yapar. **Önemli:** bu yüzden App Settings panelindeki "Appium Grid URL" alanı `/wd/hub` ile bitmeli, örn: `http://localhost:4723/wd/hub` (bu flag olmadan başlatılırsa taban yol köktür ve `/wd/hub` eklenmemeli).
- `--plugin-device-farm-platform=both` — hem Android hem iOS cihaz/emulator'lerini tarar.

Dashboard: `http://localhost:4723/device-farm` (kendi makinende **her zaman `localhost`** kullan — makinenin ağ IP'si değişebiliyor, `192.168.x.x` gibi sabit bir IP verirsen ağ değiştiğinde adres geçersiz kalır).

### Sorun giderme

Appium başlarken `EADDRINUSE` (port zaten kullanımda) hatası alırsan, portu tutan eski/takılı kalmış süreci bul ve kapat:

```bash
lsof -i :4723
kill -9 <PID>
```

Sonra Appium'u tekrar başlat.
