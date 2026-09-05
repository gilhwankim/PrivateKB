package io.privatekb.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        prefix = "privatekb.desktop",
        name = "lifecycle-enabled",
        havingValue = "true"
)
final class DesktopLifecycleController {

    static final String SHUTDOWN_TOKEN_HEADER = "X-PrivateKB-Shutdown-Token";

    private final ConfigurableApplicationContext applicationContext;
    private final byte[] expectedToken;
    private final AtomicBoolean shutdownAccepted = new AtomicBoolean();

    DesktopLifecycleController(
            ConfigurableApplicationContext applicationContext,
            @Value("${privatekb.desktop.shutdown-token:}") String expectedToken
    ) {
        this.applicationContext = applicationContext;
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/desktop/shutdown")
    ResponseEntity<Void> shutdown(
            @RequestHeader(name = SHUTDOWN_TOKEN_HEADER, required = false) String providedToken,
            HttpServletRequest request
    ) {
        if (!isLoopback(request.getRemoteAddr())) {
            return ResponseEntity.notFound().build();
        }
        if (!tokenMatches(providedToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (shutdownAccepted.compareAndSet(false, true)) {
            Thread.ofPlatform()
                    .name("privatekb-desktop-shutdown")
                    .daemon(false)
                    .start(() -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        applicationContext.close();
                    });
        }
        return ResponseEntity.accepted().build();
    }

    private boolean tokenMatches(String providedToken) {
        if (expectedToken.length == 0 || providedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken,
                providedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isLoopback(String remoteAddress) {
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
