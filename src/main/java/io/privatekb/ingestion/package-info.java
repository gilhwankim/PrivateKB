/**
 * Document ingestion module, exposed through its HTTP endpoints.
 *
 * <p>No other module currently consumes a Java API from ingestion. Implementation
 * types therefore live under {@code internal}; a public Java modifier there
 * permits sibling-package access, not access from another Modulith module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Document Ingestion",
        allowedDependencies = {"knowledge", "privacy", "platform"}
)
package io.privatekb.ingestion;
