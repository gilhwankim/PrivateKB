package io.privatekb.platform;

import io.privatekb.platform.internal.storage.LocalContentStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class PlatformStorageConfiguration {

    @Bean
    ContentStorage contentStorage(StorageProperties properties) {
        return new LocalContentStorage(properties);
    }
}
