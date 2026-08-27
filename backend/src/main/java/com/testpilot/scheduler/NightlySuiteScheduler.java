package com.testpilot.scheduler;

import com.testpilot.agent.RunStore;
import com.testpilot.controller.RunController;
import com.testpilot.model.Run;
import com.testpilot.model.TestRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/settings/nightly-time")
@CrossOrigin(origins = "*")
public class NightlySuiteScheduler {

    private final RunStore runStore;
    private final RunController runController;
    private final ThreadPoolTaskScheduler taskScheduler;

    private final AtomicInteger hour = new AtomicInteger(2);
    private final AtomicInteger minute = new AtomicInteger(0);
    private volatile ScheduledFuture<?> scheduledFuture;

    public NightlySuiteScheduler(RunStore runStore, RunController runController) {
        this.runStore = runStore;
        this.runController = runController;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(1);
        this.taskScheduler.setThreadNamePrefix("nightly-scheduler-");
        this.taskScheduler.initialize();
        reschedule();
    }

    @GetMapping
    public Map<String, Integer> getTime() {
        return Map.of("hour", hour.get(), "minute", minute.get());
    }

    @PostMapping
    public Map<String, Integer> setTime(@RequestBody Map<String, Integer> body) {
        if (body.get("hour") != null) hour.set(body.get("hour"));
        if (body.get("minute") != null) minute.set(body.get("minute"));
        reschedule();
        return getTime();
    }

    private synchronized void reschedule() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        Trigger trigger = context -> {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime nextRun = now.withHour(hour.get()).withMinute(minute.get()).withSecond(0).withNano(0);
            if (!nextRun.isAfter(now)) {
                nextRun = nextRun.plusDays(1);
            }
            return nextRun.toInstant();
        };
        scheduledFuture = taskScheduler.schedule(this::runNightlySuite, trigger);
    }

    private void runNightlySuite() {
        List<Run> all = runStore.getAll();
        for (Run template : all) {
            if (!template.isNightlySuite()) continue;

            TestRequest request = new TestRequest();
            request.setName(template.getName());
            request.setGoal(template.getGoal());
            request.setVariables(template.getVariables());
            request.setPlatform(template.getPlatform());
            request.setAppPackage(template.getAppPackage());
            request.setAppActivity(template.getAppActivity());
            request.setCaptureScreenshot(template.isCaptureScreenshot());
            request.setRecordVideo(template.isRecordVideo());
            request.setParallel(true);
            // Dashboard'daki "Gece Koşumu" bölümü bu run'ı sabah bununla tanıyor.
            request.setNightlyRun(true);

            runController.launchRun(request);

            template.setNightlySuite(false);
            runStore.save(template);
        }
    }
}