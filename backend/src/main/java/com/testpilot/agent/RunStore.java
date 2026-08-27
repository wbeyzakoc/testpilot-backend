package com.testpilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.testpilot.model.Run;
import com.testpilot.model.RunStep;
import com.testpilot.model.ScenarioSuggestion;
import com.testpilot.model.entity.RunEntity;
import com.testpilot.model.entity.RunStepEntity;
import com.testpilot.repository.RunRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class RunStore {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final File storageFile = new File("runs-history.json");
    // Diske yazma işlemi tek thread'de sıraya alınır; paralel test thread'leri
    // birbirini bu I/O için beklemez, sadece put() (non-blocking) yapıp devam eder.
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor();

    private final RunRepository runRepository;
    private final JdbcTemplate jdbcTemplate;

    public RunStore(RunRepository runRepository, JdbcTemplate jdbcTemplate) {
        this.runRepository = runRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void load() {
        fixNullNightlyRunColumn();
        if (!storageFile.exists()) {
            return;
        }
        try {
            MapType type = mapper.getTypeFactory().constructMapType(Map.class, String.class, Run.class);
            Map<String, Run> loaded = mapper.readValue(storageFile, type);
            runs.putAll(loaded);
            backfillFinishedRunsToOracle(loaded);
        } catch (IOException e) {
            System.err.println("Test geçmişi dosyası okunamadı: " + e.getMessage());
        }
    }

    // BUG FIX: "nightly_run" kolonu ddl-auto=update ile SONRADAN eklendiğinde,
    // o ana kadar Oracle'da zaten var olan satırlarda bu kolon NULL kalıyor.
    // Run.nightlyRun (ve RunEntity.nightlyRun) primitive boolean olduğu için
    // Hibernate NULL'ı oraya set edemiyor ve "Null value was assigned to a
    // property of primitive type" hatasıyla o satırı hiç okuyamıyor -- bu da
    // getAll()/findAllWithSteps() çağıran HER isteği (History dahil) patlatıyordu.
    // JdbcTemplate ile ham SQL kullanıyoruz çünkü bu düzeltme JPA transaction'ı
    // olmadan, açılışta en erken anda çalışmalı.
    private void fixNullNightlyRunColumn() {
        try {
            int updated = jdbcTemplate.update("UPDATE runs SET nightly_run = 0 WHERE nightly_run IS NULL");
            if (updated > 0) {
                System.out.println(updated + " eski test satırında nightly_run NULL'dan false'a düzeltildi.");
            }
        } catch (Exception e) {
            System.err.println("nightly_run NULL değerleri düzeltilemedi: " + e.getMessage());
        }
    }

    // TEK SEFERLİK geriye dönük taşıma (bug fix): JSON yazımı kapatılmadan (5. adım)
    // ÖNCE biten testler hiçbir zaman dual-write ile Oracle'a yazılmamıştı --
    // dual-write sadece save() çağrıldığında tetikleniyor, oysa bu eski kayıtlar
    // sadece burada, açılışta JSON'dan belleğe yükleniyordu. getAll() ise artık
    // bitmiş testleri SADECE Oracle'dan okuyor (bkz. getAll()) -- yani bu eski
    // testler History'de hiç görünmüyordu ("eski testler gözükmüyor" hatası).
    // Burada, henüz Oracle'da olmayan her bitmiş (running olmayan) run'ı bir kez
    // Oracle'a yazıp bellekten düşürüyoruz. existsById kontrolü sayesinde bir
    // sonraki açılışta (zaten Oracle'da bulunduklarından) tekrar denenmezler.
    private void backfillFinishedRunsToOracle(Map<String, Run> loaded) {
        List<Run> finished = loaded.values().stream()
                .filter(r -> !"running".equals(r.getStatus()))
                .toList();
        if (finished.isEmpty()) return;

        Set<String> existingIds = new HashSet<>();
        List<String> allIds = finished.stream().map(Run::getId).toList();
        // Oracle'ın IN listesi limiti (1000) yüzünden parça parça sorguluyoruz.
        int chunkSize = 900;
        for (int i = 0; i < allIds.size(); i += chunkSize) {
            List<String> chunk = allIds.subList(i, Math.min(i + chunkSize, allIds.size()));
            try {
                for (RunEntity e : runRepository.findAllById(chunk)) {
                    existingIds.add(e.getId());
                }
            } catch (Exception e) {
                System.err.println("Eski testler Oracle'da kontrol edilemedi: " + e.getMessage());
                return; // emin olamadığımız için hiçbir şeye dokunmuyoruz, bir dahaki açılışta tekrar denenir
            }
        }

        int migrated = 0;
        for (Run run : finished) {
            if (existingIds.contains(run.getId())) {
                runs.remove(run.getId()); // zaten Oracle'da -- bellekte ikinci bir kopya tutmaya gerek yok
                continue;
            }
            try {
                runRepository.save(toEntity(run));
                runs.remove(run.getId());
                migrated++;
            } catch (Exception e) {
                System.err.println("Eski test Oracle'a taşınamadı (" + run.getId() + "): " + e.getMessage());
            }
        }
        if (migrated > 0) {
            System.out.println("runs-history.json'dan " + migrated + " eski test Oracle'a taşındı.");
        }
    }

    // 5. adım: JSON dosyasına artık yazmıyoruz (runs-history.json çok büyümüştü) --
    // Oracle artık tek gerçek kaynak. load() geriye dönük uyumluluk için hâlâ eski
    // dosyayı açılışta bir kere okuyor, ama bundan sonra dosya hiç güncellenmiyor.
    public void save(Run run) {
        runs.put(run.getId(), run);
        scheduleOracleMirror(run);
    }

    // Aktif test (running) hâlâ bellekten geliyor -- en güncel/gecikmesiz hâl için.
    // Bellekte yoksa (örn. restart sonrası -- artık JSON'a yazmadığımız için yeni
    // testler açılışta belleğe geri yüklenmiyor), Oracle'dan tek satır olarak çekip
    // deniyoruz. Bulunamazsa null dönüyor, Controller 404'e çeviriyor (eskisi gibi).
    public Run get(String id) {
        Run run = runs.get(id);
        if (run != null) return run;
        try {
            return runRepository.findByIdWithSteps(id).map(this::fromEntity).orElse(null);
        } catch (Exception e) {
            System.err.println("Oracle'dan tek test okunamadı: " + e.getMessage());
            return null;
        }
    }
    public void delete(String id) {
        runs.remove(id);
        try {
            runRepository.deleteById(id);
        } catch (Exception e) {
            System.err.println("Oracle'dan silinemedi: " + e.getMessage());
        }
    }

    // 4. adım: geçmiş listesi artık Oracle'dan okunuyor. "running" durumundaki
    // testler ise hâlâ bellekten okunuyor -- Oracle'a yazma arka planda (async)
    // olduğu için, o an devam eden bir test için en güncel/gecikmesiz hâl bellekte.
    public List<Run> getAll() {
        List<Run> active = new ArrayList<>();
        for (Run r : runs.values()) {
            if ("running".equals(r.getStatus())) {
                active.add(r);
            }
        }
        Set<String> activeIds = new HashSet<>();
        for (Run r : active) {
            activeIds.add(r.getId());
        }

        List<Run> all = new ArrayList<>(active);
        try {
            for (RunEntity e : runRepository.findAllWithSteps()) {
                if (!activeIds.contains(e.getId())) {
                    all.add(fromEntity(e));
                }
            }
        } catch (Exception ex) {
            System.err.println("Oracle'dan geçmiş okunamadı, sadece bellekteki aktif testler gösteriliyor: " + ex.getMessage());
        }

        all.sort(Comparator.comparing(Run::getStartedAt).reversed());
        return all;
    }

    // Artık hiçbir yerden çağrılmıyor (JSON yazma kapatıldı) -- bir sorun çıkarsa
    // geri dönmek kolay olsun diye siliniyor, save()/delete() içine geri eklenebilir.
    private void schedulePersist() {
        persistExecutor.submit(this::persist);
    }

    private synchronized void persist() {
        try {
            mapper.writeValue(storageFile, runs);
        } catch (IOException e) {
            System.err.println("Test geçmişi kaydedilemedi: " + e.getMessage());
        }
    }

    // Oracle'a yazma -- artık tek gerçek kaynak burası (JSON kapatıldı, 5. adım).
    // Bir hata olursa sadece log'lanır, save() çağıranı hiç etkilemez.
    private void scheduleOracleMirror(Run run) {
        persistExecutor.submit(() -> mirrorToOracle(run));
    }

    private void mirrorToOracle(Run run) {
        try {
            RunEntity entity = toEntity(run);
            runRepository.save(entity);
        } catch (Exception e) {
            System.err.println("Oracle'a yazılamadı (JSON etkilenmedi): " + e.getMessage());
        }
    }

    private RunEntity toEntity(Run run) {
        RunEntity entity = new RunEntity();
        entity.setId(run.getId());
        entity.setName(run.getName());
        entity.setGoal(run.getGoal());
        entity.setStatus(run.getStatus());
        entity.setError(run.getError());
        entity.setStartedAt(parseInstant(run.getStartedAt()));
        entity.setFinishedAt(parseInstant(run.getFinishedAt()));
        entity.setAppPackage(run.getAppPackage());
        entity.setAppActivity(run.getAppActivity());
        entity.setPlatform(run.getPlatform());
        entity.setVariablesJson(toJson(run.getVariables()));
        entity.setCaptureScreenshot(run.isCaptureScreenshot());
        entity.setRecordVideo(run.isRecordVideo());
        entity.setHasVideo(run.isHasVideo());
        entity.setHasFailureScreenshot(run.getFailureScreenshot() != null && !run.getFailureScreenshot().isBlank());
        entity.setFailureScreenshotBase64(run.getFailureScreenshot());
        entity.setNightlySuite(run.isNightlySuite());
        entity.setNightlyRun(run.isNightlyRun());
        entity.setProjectId(run.getProjectId());
        entity.setProjectName(run.getProjectName());
        entity.setCreatedBy(run.getCreatedBy());
        entity.setSuggestionsJson(toJson(run.getSuggestions()));

        List<RunStep> steps = run.getSteps();
        if (steps != null) {
            for (RunStep s : steps) {
                RunStepEntity se = new RunStepEntity();
                se.setRun(entity);
                se.setStepNo(s.getStep());
                se.setAction(s.getAction());
                se.setTarget(s.getTarget());
                se.setReasoning(s.getReasoning());
                entity.getSteps().add(se);
            }
        }
        return entity;
    }

    // toEntity()'nin tersi -- Oracle satırından Run nesnesi kuruyor. get(id) fallback'i
    // ve getAll() (geçmiş listesi) ikisi de bunu kullanıyor.
    private Run fromEntity(RunEntity e) {
        Run run = new Run();
        run.setId(e.getId());
        run.setName(e.getName());
        run.setGoal(e.getGoal());
        run.setStatus(e.getStatus());
        run.setError(e.getError());
        run.setStartedAt(e.getStartedAt() != null ? e.getStartedAt().toString() : null);
        run.setFinishedAt(e.getFinishedAt() != null ? e.getFinishedAt().toString() : null);
        run.setAppPackage(e.getAppPackage());
        run.setAppActivity(e.getAppActivity());
        run.setPlatform(e.getPlatform());
        run.setVariables(fromJsonMap(e.getVariablesJson()));
        run.setCaptureScreenshot(e.isCaptureScreenshot());
        run.setRecordVideo(e.isRecordVideo());
        run.setHasVideo(e.isHasVideo());
        run.setNightlySuite(e.isNightlySuite());
        run.setNightlyRun(e.isNightlyRun());
        run.setFailureScreenshot(e.getFailureScreenshotBase64());
        run.setProjectId(e.getProjectId());
        run.setProjectName(e.getProjectName());
        run.setCreatedBy(e.getCreatedBy());
        run.setSuggestions(fromJsonSuggestions(e.getSuggestionsJson()));

        for (RunStepEntity se : e.getSteps()) {
            run.getSteps().add(new RunStep(se.getStepNo(), se.getAction(), se.getTarget(), se.getReasoning()));
        }
        return run;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            MapType type = mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class);
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private List<ScenarioSuggestion> fromJsonSuggestions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, ScenarioSuggestion.class));
        } catch (Exception e) {
            return null;
        }
    }
}
