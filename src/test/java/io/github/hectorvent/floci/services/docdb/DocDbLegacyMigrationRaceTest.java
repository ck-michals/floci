package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Moving a record under its regional key must not undo a delete.
 *
 * <p>The move is a read-modify-write like a tag update: read the record from the unscoped key,
 * write it under the regional one. A delete landing between the two would be undone by that write,
 * leaving a cluster no describe created and no delete can remove — so the move takes the record's
 * monitor, which is the one the delete already holds.
 */
class DocDbLegacyMigrationRaceTest {

    private StorageBackend<String, DocDbCluster> clusterStore;
    private DocDbService service;

    private void freshService() {
        clusterStore = AccountAwareStorageBackend.inMemory("000000000000");
        StorageBackend<String, DocDbInstance> instanceStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                "docdb-clusters.json".equals(inv.getArgument(1)) ? clusterStore : instanceStore);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);

        service = new DocDbService(config, new RegionResolver("us-east-1", "000000000000"),
                Mockito.mock(DocDbContainerManager.class), storageFactory);
    }

    @Test
    void readingALegacyRecordWhileItIsDeletedDoesNotBringItBack() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            freshService();
            String id = "legacy-race-" + attempt;

            // A record as an earlier Floci wrote it: under the bare identifier.
            DocDbCluster legacy = new DocDbCluster();
            legacy.setDbClusterIdentifier(id);
            legacy.setStatus("available");
            clusterStore.put(id, legacy);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread reader = new Thread(() -> {
                await(start);
                try {
                    service.getDbCluster(id);
                } catch (AwsException expected) {
                    if (!"DBClusterNotFoundFault".equals(expected.getErrorCode())) {
                        unexpected.set(expected);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread deleter = new Thread(() -> {
                await(start);
                try {
                    service.deleteDbCluster(id);
                } catch (AwsException expected) {
                    if (!"DBClusterNotFoundFault".equals(expected.getErrorCode())) {
                        unexpected.set(expected);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            reader.start();
            deleter.start();
            start.countDown();
            reader.join(TimeUnit.SECONDS.toMillis(10));
            deleter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(reader.isAlive() || deleter.isAlive(), "a thread never finished");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            // The status is reported because it names the writer: a record put back as "deleting"
            // came from the delete's own object, one filled in on read from the reader's.
            assertFalse(clusterStore.get("us-east-1::" + id).isPresent(),
                    () -> id + " came back under its regional key with status "
                            + clusterStore.get("us-east-1::" + id).map(DocDbCluster::getStatus).orElse("?"));
            assertFalse(clusterStore.get(id).isPresent(),
                    id + " was left behind under the bare key (attempt " + attempt + ")");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
