package io.privatekb.platform.localai;

import io.privatekb.platform.LocalModelPullClient;
import io.privatekb.platform.LocalModelPullException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class LocalAiModelInstallService {

    private static final int MAX_RETAINED_JOBS = 20;

    private final LocalModelPullClient pullClient;
    private final OllamaStatusClient statusClient;
    private final LocalAiReadinessService readiness;
    private final LocalAiProperties properties;
    private final ChatModelProfileProvider profiles;
    private final ExecutorService executor;
    private final Clock clock;
    private final Map<UUID, JobState> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final AtomicReference<UUID> activeJob = new AtomicReference<>();

    LocalAiModelInstallService(
            LocalModelPullClient pullClient,
            OllamaStatusClient statusClient,
            LocalAiReadinessService readiness,
            LocalAiProperties properties,
            ChatModelProfileProvider profiles,
            @Qualifier("localAiModelInstallExecutor") ExecutorService executor,
            Clock clock
    ) {
        this.pullClient = pullClient;
        this.statusClient = statusClient;
        this.readiness = readiness;
        this.properties = properties;
        this.profiles = profiles;
        this.executor = executor;
        this.clock = clock;
    }

    ModelInstallView start(LocalAiModelRole role, boolean confirmed) {
        if (!confirmed) {
            throw new ModelInstallException("MODEL_INSTALL_CONFIRMATION_REQUIRED");
        }
        String model = modelFor(role);
        UUID jobId = UUID.randomUUID();
        if (!activeJob.compareAndSet(null, jobId)) {
            throw new ModelInstallException("MODEL_INSTALL_ALREADY_RUNNING");
        }

        Instant now = Instant.now(clock);
        jobs.put(jobId, new JobState(
                jobId,
                role,
                model,
                ModelInstallStatus.QUEUED,
                0,
                0,
                0,
                null,
                now,
                now
        ));
        AtomicBoolean cancelled = new AtomicBoolean();
        cancellations.put(jobId, cancelled);
        try {
            executor.execute(() -> run(jobId, cancelled));
        } catch (RuntimeException exception) {
            activeJob.compareAndSet(jobId, null);
            cancellations.remove(jobId);
            jobs.remove(jobId);
            throw new ModelInstallException("MODEL_INSTALL_QUEUE_UNAVAILABLE");
        }
        return view(jobId);
    }

    ModelInstallView find(UUID jobId) {
        return toView(requireJob(jobId));
    }

    ModelInstallView cancel(UUID jobId) {
        JobState current = requireJob(jobId);
        if (current.status().terminal()) {
            return toView(current);
        }
        AtomicBoolean cancelled = cancellations.get(jobId);
        if (cancelled != null) {
            cancelled.set(true);
        }
        update(jobId, state -> state.withStatus(
                ModelInstallStatus.CANCEL_REQUESTED,
                null,
                Instant.now(clock)
        ));
        return view(jobId);
    }

    private void run(UUID jobId, AtomicBoolean cancelled) {
        try {
            if (cancelled.get()) {
                markCancelled(jobId);
                return;
            }
            JobState job = requireJob(jobId);
            if (installed(job.model())) {
                complete(jobId);
                return;
            }

            update(jobId, state -> state.withStatus(
                    ModelInstallStatus.DOWNLOADING,
                    null,
                    Instant.now(clock)
            ));
            pullClient.pull(
                    job.model(),
                    progress -> updateProgress(jobId, progress.completedBytes(), progress.totalBytes()),
                    cancelled::get
            );
            if (cancelled.get()) {
                markCancelled(jobId);
                return;
            }

            update(jobId, state -> state.withStatus(
                    ModelInstallStatus.VERIFYING,
                    null,
                    Instant.now(clock)
            ));
            if (!installed(job.model())) {
                fail(jobId, "MODEL_INSTALL_VERIFY_FAILED");
                return;
            }
            complete(jobId);
        } catch (LocalModelPullException exception) {
            if (cancelled.get()) {
                markCancelled(jobId);
            } else {
                fail(jobId, errorCode(exception.reason()));
            }
        } catch (RuntimeException exception) {
            if (cancelled.get()) {
                markCancelled(jobId);
            } else {
                fail(jobId, "MODEL_INSTALL_FAILED");
            }
        } finally {
            activeJob.compareAndSet(jobId, null);
            cancellations.remove(jobId);
            trimFinishedJobs();
        }
    }

    private boolean installed(String model) {
        OllamaProbeResult probe = statusClient.probe();
        if (probe.status() != OllamaConnectionStatus.CONNECTED) {
            throw new LocalModelPullException(
                    LocalModelPullException.Reason.OLLAMA_NOT_RUNNING
            );
        }
        return probe.models().stream().anyMatch(candidate -> model.equals(candidate.name()));
    }

    private void complete(UUID jobId) {
        update(jobId, state -> new JobState(
                state.jobId(),
                state.role(),
                state.model(),
                ModelInstallStatus.COMPLETED,
                100,
                state.totalBytes() > 0 ? state.totalBytes() : state.completedBytes(),
                state.totalBytes(),
                null,
                state.createdAt(),
                Instant.now(clock)
        ));
        readiness.triggerCheck();
    }

    private void markCancelled(UUID jobId) {
        update(jobId, state -> state.withStatus(
                ModelInstallStatus.CANCELLED,
                null,
                Instant.now(clock)
        ));
    }

    private void fail(UUID jobId, String errorCode) {
        update(jobId, state -> state.withStatus(
                ModelInstallStatus.FAILED,
                errorCode,
                Instant.now(clock)
        ));
    }

    private void updateProgress(UUID jobId, long completedBytes, long totalBytes) {
        jobs.computeIfPresent(jobId, (ignored, state) -> {
            if (state.status() == ModelInstallStatus.CANCEL_REQUESTED) {
                return state;
            }
            int currentProgress = totalBytes > 0
                    ? (int) Math.min(99, Math.floor(
                            (double) completedBytes * 100.0 / (double) totalBytes
                    ))
                    : state.progressPercent();
            return new JobState(
                    state.jobId(),
                    state.role(),
                    state.model(),
                    ModelInstallStatus.DOWNLOADING,
                    currentProgress,
                    completedBytes,
                    totalBytes,
                    null,
                    state.createdAt(),
                    Instant.now(clock)
            );
        });
    }

    private void update(UUID jobId, java.util.function.UnaryOperator<JobState> updater) {
        jobs.computeIfPresent(jobId, (ignored, state) -> updater.apply(state));
    }

    private JobState requireJob(UUID jobId) {
        JobState job = jobs.get(jobId);
        if (job == null) {
            throw new ModelInstallException("MODEL_INSTALL_NOT_FOUND");
        }
        return job;
    }

    private ModelInstallView view(UUID jobId) {
        return toView(requireJob(jobId));
    }

    private ModelInstallView toView(JobState job) {
        return new ModelInstallView(
                job.jobId(),
                job.role(),
                job.model(),
                job.status(),
                job.progressPercent(),
                job.completedBytes(),
                job.totalBytes(),
                job.errorCode(),
                job.createdAt(),
                job.updatedAt()
        );
    }

    private String modelFor(LocalAiModelRole role) {
        if (role == null) {
            throw new ModelInstallException("MODEL_ROLE_INVALID");
        }
        return switch (role) {
            case EMBEDDING -> properties.embeddingModel();
            case CHAT -> selectedChatModel();
        };
    }

    private String selectedChatModel() {
        ChatModelProfile profile = profiles.current();
        if (!profile.enabled()) {
            throw new ModelInstallException("CHAT_MODEL_DISABLED");
        }
        return profile.model();
    }

    private String errorCode(LocalModelPullException.Reason reason) {
        return switch (reason) {
            case OLLAMA_NOT_RUNNING -> "OLLAMA_NOT_RUNNING";
            case REQUEST_FAILED -> "MODEL_DOWNLOAD_FAILED";
            case STREAM_INVALID -> "MODEL_DOWNLOAD_STREAM_INVALID";
        };
    }

    private void trimFinishedJobs() {
        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        jobs.values().stream()
                .filter(job -> job.status().terminal())
                .sorted(java.util.Comparator.comparing(JobState::updatedAt))
                .limit(jobs.size() - MAX_RETAINED_JOBS)
                .map(JobState::jobId)
                .forEach(jobs::remove);
    }

    private record JobState(
            UUID jobId,
            LocalAiModelRole role,
            String model,
            ModelInstallStatus status,
            int progressPercent,
            long completedBytes,
            long totalBytes,
            String errorCode,
            Instant createdAt,
            Instant updatedAt
    ) {
        JobState withStatus(
                ModelInstallStatus newStatus,
                String newErrorCode,
                Instant now
        ) {
            return new JobState(
                    jobId,
                    role,
                    model,
                    newStatus,
                    progressPercent,
                    completedBytes,
                    totalBytes,
                    newErrorCode,
                    createdAt,
                    now
            );
        }
    }
}
