package io.privatekb.ingestion.internal.indexing;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.privatekb.ingestion.internal.application.IndexingApplicationService;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.SourceCopyCleanup;
import io.privatekb.ingestion.internal.config.IndexingProperties;
import io.privatekb.platform.ContentStorage;
import io.privatekb.platform.ContentStorageException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IndexingStartupListenerTest {

    @Test
    void removesSourceCopiesLeftByCompletedDocumentsAndMarksThemClean() {
        IndexingApplicationService indexing = mock(IndexingApplicationService.class);
        IndexingJobStore jobs = mock(IndexingJobStore.class);
        ContentStorage storage = mock(ContentStorage.class);
        UUID versionId = UUID.randomUUID();
        SourceCopyCleanup source = new SourceCopyCleanup(versionId, "workspace/version/source.bin");
        when(jobs.findCompletedSourceCopiesPendingCleanup(500))
                .thenReturn(List.of(source))
                .thenReturn(List.of());

        listener(indexing, jobs, storage).resumePendingJobs();

        verify(storage).delete(source.sourceStorageKey());
        verify(jobs).markSourceCopyDeleted(versionId);
        verify(jobs, times(2)).findCompletedSourceCopiesPendingCleanup(500);
        verify(jobs).purgeExpiredStaging(Duration.ofHours(72));
        verify(jobs).recoverInterruptedJobs();
        verify(indexing).resumeAvailable();
    }

    @Test
    void leavesCleanupPendingWhenTheFileCannotBeDeleted() {
        IndexingApplicationService indexing = mock(IndexingApplicationService.class);
        IndexingJobStore jobs = mock(IndexingJobStore.class);
        ContentStorage storage = mock(ContentStorage.class);
        UUID versionId = UUID.randomUUID();
        SourceCopyCleanup source = new SourceCopyCleanup(versionId, "workspace/version/source.bin");
        when(jobs.findCompletedSourceCopiesPendingCleanup(500)).thenReturn(List.of(source));
        doThrow(new ContentStorageException("synthetic failure"))
                .when(storage).delete(source.sourceStorageKey());

        listener(indexing, jobs, storage).resumePendingJobs();

        verify(jobs, never()).markSourceCopyDeleted(versionId);
        verify(jobs).findCompletedSourceCopiesPendingCleanup(500);
        verify(indexing).resumeAvailable();
    }

    private IndexingStartupListener listener(
            IndexingApplicationService indexing,
            IndexingJobStore jobs,
            ContentStorage storage
    ) {
        return new IndexingStartupListener(
                indexing,
                jobs,
                new IndexingProperties(1200, 180, 16, 64, 3, Duration.ofHours(72), 3),
                storage
        );
    }
}
