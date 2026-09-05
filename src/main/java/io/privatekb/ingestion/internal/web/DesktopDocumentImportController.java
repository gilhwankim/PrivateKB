package io.privatekb.ingestion.internal.web;

import io.privatekb.ingestion.internal.application.command.DesktopDocumentImportCommand;
import io.privatekb.ingestion.internal.application.DesktopDocumentImportService;
import io.privatekb.ingestion.internal.application.view.UploadDocumentResult;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnProperty(prefix = "privatekb.desktop", name = "bridge-enabled", havingValue = "true")
final class DesktopDocumentImportController {

    static final String DESKTOP_TOKEN_HEADER = "X-PrivateKB-Desktop-Token";

    private final DesktopDocumentImportService imports;
    private final byte[] expectedToken;

    DesktopDocumentImportController(
            DesktopDocumentImportService imports,
            @Value("${privatekb.desktop.bridge-token:}") String expectedToken
    ) {
        this.imports = imports;
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(
            path = "/api/desktop/workspaces/{workspaceId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<UploadDocumentResult> upload(
            @PathVariable UUID workspaceId,
            @RequestHeader(name = DESKTOP_TOKEN_HEADER, required = false) String providedToken,
            @RequestPart("file") MultipartFile file,
            @RequestParam String originalFilename,
            @RequestParam(defaultValue = "application/octet-stream") String declaredMediaType,
            @RequestParam long lastModifiedMillis,
            @RequestParam String sourcePath,
            @RequestParam(required = false) String sourceRootPath,
            @RequestParam(required = false) String relativePath,
            HttpServletRequest request
    ) {
        if (!isLoopback(request.getRemoteAddr())) {
            return ResponseEntity.notFound().build();
        }
        if (!tokenMatches(providedToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UploadDocumentResult result = imports.submit(new DesktopDocumentImportCommand(
                workspaceId,
                originalFilename,
                declaredMediaType,
                file.getSize(),
                lastModifiedMillis,
                sourcePath,
                sourceRootPath,
                relativePath,
                file::getInputStream
        ));
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/ingestions/" + result.ingestionJobId()))
                .body(result);
    }

    private boolean tokenMatches(String providedToken) {
        return expectedToken.length > 0
                && providedToken != null
                && MessageDigest.isEqual(
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
