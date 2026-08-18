package com.testpilot.appium;

import com.testpilot.agent.RunStore;
import com.testpilot.model.Run;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Component
public class VideoCleanupService {

    @Value("${video.retention-days:7}")
    private int retentionDays;

    private final RunStore runStore;

    public VideoCleanupService(RunStore runStore) {
        this.runStore = runStore;
    }

    @Scheduled(cron = "0 0 3 * * *") // her gün gece 03:00'te çalışır
    public void cleanupOldVideos() {
        Path dir = Paths.get("videos");
        if (!Files.exists(dir)) return;

        long cutoffMillis = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000);

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".mp4")).forEach(p -> {
                try {
                    long lastModified = Files.getLastModifiedTime(p).toMillis();
                    if (lastModified < cutoffMillis) {
                        String runId = p.getFileName().toString().replace(".mp4", "");
                        Files.deleteIfExists(p);
                        Run run = runStore.get(runId);
                        if (run != null) {
                            run.setHasVideo(false);
                            runStore.save(run);
                        }
                        System.out.println("Eski video silindi: " + p.getFileName());
                    }
                } catch (IOException e) {
                    System.out.println("Video silinirken hata: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.out.println("Video klasörü taranırken hata: " + e.getMessage());
        }
    }
}