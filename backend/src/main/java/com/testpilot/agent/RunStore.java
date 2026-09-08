package com.testpilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.testpilot.model.Run;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Runlar artik runs-history.json yerine Oracle'da (MOBILE_RUNS/MOBILE_RUN_STEPS)
// tutuluyor -- gercek okuma/yazma islerini RunPersistenceService (JPA) yapiyor.
// Bu sinif hala bellekte bir kopya (runs map'i) tutuyor, cunku bir test kosarken
// (RunController.executeRun) run nesnesinin AYNI referansi uzerinden okunup
// yaziliyor -- stopRequested ve canli steps listesi bu yuzden onemli. Her
// okumada DB'den yeni bir POJO olusturursak stopRun()'daki
// run.setStopRequested(true) calisan thread'e hic ulasmaz. Yani bellek hala
// "gercek zamanli" kaynak, Oracle ise kalicilik/dayaniklilik katmani.
//
// Ilk acilista (@PostConstruct load()) once Oracle'daki mevcut kayitlar
// belleğe yukleniyor. Oracle bombosysa VE eski runs-history.json dosyasi hala
// diskte varsa, o dosya BIR KERELIGINE Oracle'a aktarilip belleğe de
// yukleniyor -- boylece gecmis test kayitlari kaybolmuyor. Bu aktarimdan
// sonra JSON dosyasina bir daha hic yazilmiyor/okunmuyor (sadece ilk goc icin
// duruyor, silmek istersen artik guvenle silinebilir).
@Component
public class RunStore {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final RunPersistenceService persistenceService;
    private final File legacyJsonFile = new File("runs-history.json");
    private final ObjectMapper legacyMapper = new ObjectMapper();
    // Oracle'a yazma islemi tek thread'de sıraya alınır; paralel test thread'leri
    // birbirini bu I/O icin beklemez, sadece put() (non-blocking) yapip devam eder.
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor();

    public RunStore(RunPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @PostConstruct
    public void load() {
        List<Run> fromDb = persistenceService.loadAll();
        if (!fromDb.isEmpty()) {
            for (Run r : fromDb) {
                runs.put(r.getId(), r);
            }
            return;
        }

        if (!legacyJsonFile.exists()) {
            return;
        }
        try {
            MapType type = legacyMapper.getTypeFactory().constructMapType(Map.class, String.class, Run.class);
            Map<String, Run> legacy = legacyMapper.readValue(legacyJsonFile, type);
            for (Run r : legacy.values()) {
                runs.put(r.getId(), r);
                persistenceService.importFromJson(r);
            }
            System.out.println("runs-history.json'daki " + legacy.size() + " test Oracle'a aktarildi.");
        } catch (Exception e) {
            System.err.println("Eski test gecmisi (runs-history.json) okunamadi: " + e.getMessage());
        }
    }

    public void save(Run run) {
        runs.put(run.getId(), run);
        persistExecutor.submit(() -> {
            try {
                persistenceService.save(run);
            } catch (Exception e) {
                System.err.println("Test Oracle'a kaydedilemedi (id=" + run.getId() + "): " + e.getMessage());
            }
        });
    }

    public Run get(String id) {
        return runs.get(id);
    }

    public void delete(String id) {
        runs.remove(id);
        persistExecutor.submit(() -> {
            try {
                persistenceService.delete(id);
            } catch (Exception e) {
                System.err.println("Test Oracle'dan silinemedi (id=" + id + "): " + e.getMessage());
            }
        });
    }

    public List<Run> getAll() {
        List<Run> all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing(Run::getStartedAt).reversed());
        return all;
    }
}
