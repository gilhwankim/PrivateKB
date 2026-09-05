package io.privatekb.platform;

import java.nio.file.Path;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("privatekb.storage")
@Validated
public record StorageProperties(
        Path root,
        @Min(0) long minimumFreeBytes
) {
}
