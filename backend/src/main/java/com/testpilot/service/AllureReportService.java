package com.testpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.model.Run;
import com.testpilot.model.RunStep;
import com.testpilot.model.SuiteRef;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Dashboard'daki "Allure Raporu Oluştur" butonunun arkasındaki servis.
//
// Bu proje JUnit/TestNG gibi standart bir test çatısı KULLANMIYOR -- testler
// LlmAgent tarafından ajan odaklı çalıştırılıyor, sonuçlar Run/RunStep
// POJO'larında (Oracle'da) tutuluyor. Dolayısıyla Allure'ın kendi Java
// kütüphanesini (JUnit/TestNG listener'ı) kullanamıyoruz. Bunun yerine
// Allure'ın "allure-results" JSON şemasını KENDİMİZ elle üretip, kullanıcının
// bilgisayarında zaten kurulu olan `allure` CLI'ını ("allure generate")
// çağırarak GERÇEK bir Allure HTML raporu üretiyoruz -- ortaya çıkan sonuç,
// JUnit'ten üretilmiş bir Allure raporuyla birebir aynı görünür/davranır
// (zaman çizelgesi, kategori dağılımı, geçmiş/trend grafiği dahil).
@Service
public class iAllureReportService {

    private final ObjectMapper mapper = new ObjectMapper();

    // Üretilen statik Allure sitesi buraya yazılıyor -- StaticResourceConfig
    // bu klasörü /allure-report/** olarak dışarıya açıyor (mevcut videos/
    // ile birebir aynı desen).
    private static final Path REPORT_DIR = Path.of("allure-report");

    public static class GenerationResult {
        public final boolean success;
        public final String message;
        public final int includedCount;

        public GenerationResult(boolean success, String message, int includedCount) {
            this.success = success;
            this.message = message;
            this.includedCount = includedCount;
        }
    }

    // Dashboard'daki "Raporu Temizle" butonu -- daha önce üretilmiş statik
    // Allure sitesini tamamen siliyor (bir sonraki "Rapor Oluştur" çağrısı
    // klasörü yeniden oluşturuyor zaten).
    public boolean clear() {
        try {
            deleteRecursively(REPORT_DIR);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public GenerationResult generate(List<Run> runs) {
        // "running" durumundaki (henüz bitmemiş) testlerin Allure'da anlamı
        // yok -- durumu/süresi belirsiz, rapordan dışarıda bırakılıyor.
        List<Run> finished = runs.stream()
                .filter(r -> r.getStatus() != null && !"running".equals(r.getStatus()))
                .filter(r -> r.getStartedAt() != null && r.getFinishedAt() != null)
                .toList();

        if (finished.isEmpty()) {
            return new GenerationResult(false,
                    "Rapor oluşturmak için tamamlanmış (passed/failed) en az bir test gerekiyor.", 0);
        }

        Path resultsDir;
        try {
            resultsDir = Files.createTempDirectory("allure-results-");
        } catch (IOException e) {
            return new GenerationResult(false, "Geçici klasör oluşturulamadı: " + e.getMessage(), 0);
        }

        try {
            for (Run run : finished) {
                writeResultJson(resultsDir, run);
            }

            Files.createDirectories(REPORT_DIR);
            ProcessBuilder pb = new ProcessBuilder(
                    "allure", "generate", resultsDir.toAbsolutePath().toString(),
                    "-o", REPORT_DIR.toAbsolutePath().toString(),
                    "--clean"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (var is = process.getInputStream()) {
                output = new String(is.readAllBytes());
            }
            boolean finishedInTime = process.waitFor(60, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                return new GenerationResult(false, "Allure CLI zaman aşımına uğradı.", finished.size());
            }
            if (process.exitValue() != 0) {
                return new GenerationResult(false, "Allure CLI hata verdi: " + output, finished.size());
            }
            return new GenerationResult(true, "Rapor oluşturuldu.", finished.size());
        } catch (IOException e) {
            return new GenerationResult(false,
                    "Allure CLI çalıştırılamadı -- bilgisayarında `allure` komutu PATH üzerinde mi? (" + e.getMessage() + ")",
                    finished.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GenerationResult(false, "İşlem kesildi.", finished.size());
        } finally {
            deleteRecursively(resultsDir);
        }
    }

    private void writeResultJson(Path resultsDir, Run run) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        String uuid = isUuid(run.getId()) ? run.getId() : UUID.randomUUID().toString();
        result.put("uuid", uuid);
        // historyId: Allure ayni historyId'ye sahip sonuclari "retry" (aynı
        // testin tekrar denemesi) sayip TEK bir test olarak gosterip
        // digerlerini gizliyor -- goal metnine gore ureseydik (ayni "giris
        // yap" hedefiyle onlarca kez calistirilmis testler gibi), gercekte
        // BAGIMSIZ onlarca run TEK bir test case'e collapse olurdu ve toplam
        // test say1s1 oldugundan COK az gorunurdu (tam da yasanan sorun bu).
        // Bu projede her Run kendi basina bagimsiz bir test case sayilmali,
        // bu yuzden historyId'yi de uuid kadar essiz tutuyoruz -- higbir run
        // digerinin retry'i sayilip gizlenmiyor.
        result.put("historyId", uuid);
        result.put("fullName", run.getGoal());
        result.put("name", run.getName() != null && !run.getName().isBlank() ? run.getName() : shorten(run.getGoal()));
        result.put("status", mapStatus(run.getStatus()));

        if (run.getError() != null && !run.getError().isBlank()) {
            Map<String, Object> statusDetails = new LinkedHashMap<>();
            statusDetails.put("message", run.getError());
            result.put("statusDetails", statusDetails);
        }

        result.put("stage", "finished");

        long start = parseMillis(run.getStartedAt());
        long stop = parseMillis(run.getFinishedAt());
        if (stop < start) stop = start;
        result.put("start", start);
        result.put("stop", stop);

        List<Map<String, Object>> steps = new ArrayList<>();
        List<RunStep> runSteps = run.getSteps();
        if (runSteps != null && !runSteps.isEmpty()) {
            long span = Math.max(stop - start, runSteps.size());
            long per = Math.max(span / runSteps.size(), 1);
            long cursor = start;
            for (RunStep rs : runSteps) {
                Map<String, Object> step = new LinkedHashMap<>();
                String label = (rs.getAction() != null ? rs.getAction() : "adım");
                if (rs.getTarget() != null && !rs.getTarget().isBlank()) label += " → " + rs.getTarget();
                if (rs.getReasoning() != null && !rs.getReasoning().isBlank()) label += " (" + rs.getReasoning() + ")";
                step.put("name", label);
                step.put("status", "failed".equals(rs.getAction()) ? "failed" : "passed");
                step.put("stage", "finished");
                step.put("start", cursor);
                cursor = Math.min(cursor + per, stop);
                step.put("stop", cursor);
                steps.add(step);
            }
        }
        result.put("steps", steps);

        // Hata anında alınan ekran görüntüsü (Run.failureScreenshot, base64) --
        // Allure'da bir "attachment" olarak ayrı bir dosyaya yazılıp result
        // JSON'ından bu dosyaya referans veriliyor; Allure raporunda o testin
        // sayfasında doğrudan görüntülenebiliyor.
        List<Map<String, String>> attachments = new ArrayList<>();
        String screenshotBase64 = run.getFailureScreenshot();
        if (screenshotBase64 != null && !screenshotBase64.isBlank()) {
            try {
                String data = screenshotBase64;
                int commaIdx = data.indexOf(',');
                if (data.startsWith("data:") && commaIdx != -1) {
                    data = data.substring(commaIdx + 1); // "data:image/png;base64,..." önekini at
                }
                byte[] bytes = Base64.getDecoder().decode(data);
                String attachmentFileName = uuid + "-attachment.png";
                Files.write(resultsDir.resolve(attachmentFileName), bytes);
                Map<String, String> attachment = new LinkedHashMap<>();
                attachment.put("name", "Hata Anı Ekran Görüntüsü");
                attachment.put("source", attachmentFileName);
                attachment.put("type", "image/png");
                attachments.add(attachment);
            } catch (IllegalArgumentException ignored) {
                // base64 çözümlenemedi -- ekran görüntüsü olmadan devam
            }
        }
        result.put("attachments", attachments);

        List<Map<String, String>> labels = new ArrayList<>();
        labels.add(label("framework", "BEQA"));
        if (run.getProjectName() != null) labels.add(label("suite", run.getProjectName()));
        List<SuiteRef> suites = run.getSuites();
        if (suites != null) {
            for (SuiteRef s : suites) {
                labels.add(label("feature", s.getName()));
            }
        }
        if (run.getPlatform() != null) labels.add(label("host", run.getPlatform()));
        if (run.getCreatedBy() != null) labels.add(label("owner", run.getCreatedBy()));
        result.put("labels", labels);

        Path file = resultsDir.resolve(uuid + "-result.json");
        mapper.writeValue(file.toFile(), result);
    }

    private static Map<String, String> label(String name, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private static String mapStatus(String status) {
        if (status == null) return "unknown";
        return switch (status) {
            case "passed" -> "passed";
            case "failed" -> "failed";
            case "error" -> "broken";
            case "stopped" -> "skipped";
            default -> "unknown";
        };
    }

    private static String shorten(String s) {
        if (s == null) return "İsimsiz Test";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static long parseMillis(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static boolean isUuid(String s) {
        if (s == null) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
