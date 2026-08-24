# Paralel Test Koşumu Kurulumu (Selenium Grid)

Bu proje, testleri **tek platformda sırayla** (local) ya da **Selenium Grid üzerinden paralel** (Android + iOS aynı anda) çalıştırabilir. Hangi mod kullanılacağı, test isteğindeki `parallel` alanıyla belirlenir (`true`/`false`).

## Gereksinimler

- Appium kurulu olmalı (`npm install -g appium`), `uiautomator2` ve `xcuitest` driver'ları eklenmiş olmalı.
- Android Studio (emulator'ler için) / Xcode (iOS Simulator için, sadece macOS).
- [Selenium Server](https://github.com/SeleniumHQ/selenium/releases) jar dosyası (`selenium-server-<versiyon>.jar`), Grid modunda kullanılacaksa indirilmeli.

## 1) Local (paralel olmayan) koşum

Grid'e hiç gerek yok. Sadece platforma özel Appium instance'larını ayakta tutmanız yeterli:

```bash
# Android
appium --port 4723 --default-capabilities '{"appium:udid":"<emulator-id>"}'

# iOS (macOS)
appium --port 4727 --default-capabilities '{"appium:udid":"<simulator-udid>"}'
```

`<emulator-id>` için: `adb devices` (örn. `emulator-5554`).
`<simulator-udid>` için: `xcrun simctl list devices` (booted olan simulator'ün UDID'i).

Test isteğinde `parallel: false` gönderin (veya alanı hiç göndermeyin). `application.properties` içindeki `appium.server-url` (Android) ve `appium.ios-server-url` (iOS) adreslerine göre bağlanılır.

> **Not (iOS):** `udid` verildiğinde `platformVersion`/`deviceName` göndermenize gerek yok — Appium doğrudan o simulator'ü hedefler. `udid` vermezseniz Appium kendi varsayılanını seçmeye çalışabilir, bu da Xcode'un bundled SDK'sı ile kurulu simulator runtime'ı farklıysa hataya yol açabilir.

## 2) Paralel koşum (Selenium Grid)

### Adım 1 — Cihazları açın

- İki Android emulator başlatın (Android Studio ya da `emulator -avd <isim>`).
- (macOS) Bir iOS Simulator başlatın.

### Adım 2 — Appium instance'larını udid pinli başlatın

```bash
# Android emulator 1
appium --port 4723 --default-capabilities '{"appium:udid":"emulator-5554"}'

# Android emulator 2
appium --port 4726 --default-capabilities '{"appium:udid":"emulator-5556"}'

# iOS simulator (macOS)
appium --port 4727 --default-capabilities '{"appium:udid":"<simulator-udid>"}'
```

Her bir emulator/simulator'ün gerçek `udid`/`emulator-id` değerini kendi makinenizde `adb devices` ve `xcrun simctl list devices` ile bulun — yukarıdaki değerler örnektir, farklıysa güncelleyin.

### Adım 3 — Grid Hub'ı başlatın

```bash
java -jar selenium-server-*.jar hub --port 4444
```

### Adım 4 — Grid Node'larını başlatın

Her node, ilgili Appium instance'ına relay yapan küçük bir `.toml` config ile başlatılır. Örnek config'ler:

**node1.toml** (Android emulator 1 → 4723)
```toml
[server]
port = 5555
[relay]
url = "http://localhost:4723"
status-endpoint = "/status"
configs = ["1", "{\"platformName\": \"Android\", \"appium:automationName\": \"UiAutomator2\"}"]
```

**node2.toml** (Android emulator 2 → 4726)
```toml
[server]
port = 5556
[relay]
url = "http://localhost:4726"
status-endpoint = "/status"
configs = ["1", "{\"platformName\": \"Android\", \"appium:automationName\": \"UiAutomator2\"}"]
```

**node3.toml** (iOS simulator → 4727)
```toml
[server]
port = 5600
[relay]
url = "http://localhost:4727"
status-endpoint = "/status"
configs = ["1", "{\"platformName\": \"IOS\", \"appium:automationName\": \"XCUITest\"}"]
```

Bu dosyaları Selenium Server jar'ının bulunduğu klasöre koyup şu şekilde başlatın:

```bash
java -jar selenium-server-*.jar node --config node1.toml --hub http://localhost:4444
java -jar selenium-server-*.jar node --config node2.toml --hub http://localhost:4444
java -jar selenium-server-*.jar node --config node3.toml --hub http://localhost:4444
```

Grid durumunu kontrol etmek için: `http://localhost:4444/ui`

### Adım 5 — Testi çalıştırın

Test isteğinde `parallel: true` gönderin. Backend, `application.properties`'teki `appium.grid-url` (`http://127.0.0.1:4444`) üzerinden Grid'e bağlanır ve Grid, uygun node'a yönlendirir.

## application.properties özet

```properties
appium.server-url=http://127.0.0.1:4723      # Android local (parallel:false)
appium.ios-server-url=http://127.0.0.1:4727  # iOS local (parallel:false)
appium.grid-url=http://127.0.0.1:4444        # Grid hub (parallel:true)
```

## Sık karşılaşılan sorun

`'<versiyon>' does not exist in the list of simctl SDKs` hatası alırsanız: Appium instance'ını `udid` ile pinlemediğiniz için Xcode'un varsayılan SDK'sını denemiş, ama o SDK/runtime makinenizde kurulu değil demektir. Çözüm: Appium'u yukarıdaki gibi mutlaka `--default-capabilities '{"appium:udid":"..."}'` ile başlatın.
