package io.privatekb.platform;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface LocalModelPullClient {

    void pull(
            String model,
            Consumer<ModelPullProgress> progressConsumer,
            BooleanSupplier cancelled
    );

    record ModelPullProgress(long completedBytes, long totalBytes) {
    }
}
