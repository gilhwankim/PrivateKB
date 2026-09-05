package io.privatekb.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 경로는 토큰을 가진 네이티브 셸에만 전달하며 일반 검색·브라우저에는 반환하지 않는다. */
@RestController
@ConditionalOnProperty(prefix = "privatekb.desktop", name = "bridge-enabled", havingValue = "true")
final class DesktopDocumentSourceController {
    private static final UUID LOCAL_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final DocumentSourceCatalog sources;
    private final byte[] expectedToken;

    DesktopDocumentSourceController(DocumentSourceCatalog sources,
            @Value("${privatekb.desktop.bridge-token:}") String token) {
        this.sources = sources;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/api/desktop/workspaces/{workspaceId}/document-versions/{versionId}/source-locations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SourceLocations> find(@PathVariable UUID workspaceId, @PathVariable UUID versionId,
            @RequestHeader(name = "X-PrivateKB-Desktop-Token", required = false) String token,
            HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remote)) {
            return ResponseEntity.notFound().build();
        }
        if (request.getHeader("Origin") != null || expectedToken.length == 0 || token == null
                || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // 현재 데스크톱 세션은 기본 로컬 작업공간만 사용한다.
        if (!LOCAL_WORKSPACE.equals(workspaceId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new SourceLocations(sources.findLocations(workspaceId, versionId)));
    }

    record SourceLocations(List<DocumentSourceCatalog.DocumentSourceLocation> locations) {
    }
}
