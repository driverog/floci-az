package io.floci.az.core.docker;

import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import com.github.dockerjava.api.model.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageCacheServiceTest {

    private static final String IMAGE = "mcr.microsoft.com/azure-storage/azurite:latest";

    @Test
    void succeedsOnFirstAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCacheService.runWithRetry(IMAGE, 3, 1L, calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOnTransient500AndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 3) {
                throw new InternalServerErrorException(
                        "Status 500: {\"message\":\"toomanyrequests: Rate exceeded\"}");
            }
        });
        assertEquals(3, calls.get());
    }

    @Test
    void exhaustsAttemptsAndRethrowsLast500() {
        AtomicInteger calls = new AtomicInteger();
        InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new InternalServerErrorException("backend unavailable");
                }));
        assertEquals(3, calls.get());
        assertTrue(ex.getMessage().contains("backend unavailable"));
    }

    @Test
    void doesNotRetryOnNotFound() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(NotFoundException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new NotFoundException("manifest unknown");
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotRetryOnUnauthorized() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(UnauthorizedException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new UnauthorizedException("denied");
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotRetryOnGenericDockerException() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DockerException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new DockerException("connection refused", -1);
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOnPullWrapperDockerClientExceptionAndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
            if (calls.incrementAndGet() < 2) {
                throw new DockerClientException(
                        "Could not pull image: toomanyrequests: Rate exceeded");
            }
        });
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryOnNonPullDockerClientException() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DockerClientException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new DockerClientException("container start failed: exit 137");
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void propagatesInterrupted() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(InterruptedException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new InterruptedException("interrupted mid-pull");
                }));
        assertEquals(1, calls.get());
    }

    // ── Cache identity ─────────────────────────────────────────────────────────

    /**
     * The pull cache was keyed on the image alone, so the first caller's pull answered every
     * later one: a container group supplying different — or invalid — registry credentials for an
     * image another group had already fetched was never authenticated against the registry.
     */
    private static AuthConfig auth(String server, String username, String password) {
        return new AuthConfig()
                .withRegistryAddress(server)
                .withUsername(username)
                .withPassword(password);
    }

    @Test
    void cacheKeyIsTheImageWhenNoCredentialsAreSupplied() {
        assertEquals(IMAGE, ImageCacheService.cacheKey(IMAGE, null));
    }

    @Test
    void cacheKeyIsStableForTheSameCredentials() {
        assertEquals(ImageCacheService.cacheKey(IMAGE, auth("reg.example.com", "alice", "one")),
                ImageCacheService.cacheKey(IMAGE, auth("reg.example.com", "alice", "one")));
    }

    @Test
    void cacheKeySeparatesCallersWithDifferentCredentials() {
        String alice = ImageCacheService.cacheKey(IMAGE, auth("reg.example.com", "alice", "one"));
        assertNotEquals(alice, ImageCacheService.cacheKey(IMAGE, auth("reg.example.com", "alice", "two")),
                "a different password must not be answered from another caller's pull");
        assertNotEquals(alice, ImageCacheService.cacheKey(IMAGE, auth("reg.example.com", "bob", "one")),
                "a different user must not be answered from another caller's pull");
        assertNotEquals(alice, ImageCacheService.cacheKey(IMAGE, null),
                "an anonymous pull must not be answered from an authenticated one");
    }

    @Test
    void cacheKeyDoesNotRetainTheCredentialInClearText() {
        assertFalse(ImageCacheService.cacheKey(IMAGE, auth("reg.example.com", "alice", "s3cr3t-token"))
                        .contains("s3cr3t-token"),
                "the key is long-lived, so the password must only take part as a digest");
    }
}
