package com.testpilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.testpilot.model.Run;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    @PostConstruct
    public void load() {
        if (!storageFile.exists()) {
            return;
        }
        try {
            MapType type = mapper.getTypeFactory().constructMapType(Map.class, String.class, Run.class);
            Map<String, Run> loaded = mapper.readValue(storageFile, type);
            runs.putAll(loaded);
        } catch (IOException e) {
            System.err.println("Test geçmişi dosyası okunamadı: " + e.getMessage());
        }
    }

    public void save(Run run) {
        runs.put(run.getId(), run);
        schedulePersist();
    }

    public Run get(String id) {
        return runs.get(id);
    }
    public void delete(String id) {
        runs.remove(id);
        schedulePersist();
    }
    public List<Run> getAll() {
        List<Run> all = new ArrayList<>(runs.values());
        all.sort(Comparator.comparing(Run::getStartedAt).reversed());
        return all;
    }

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
}