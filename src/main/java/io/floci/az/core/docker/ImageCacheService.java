package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.AuthConfig;
import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ensures each Docker image is pulled only once per process lifetime.
 */
@ApplicationScoped
public class ImageCacheService {

    private static final Logger LOG = Logger.getLogger(ImageCacheService.class);

    static final int MAX_PULL_ATTEMPTS = 3;
    static final long INITIAL_BACKOFF_MS = 500L;

    private final DockerClient dockerClient;
    private final List<EmulatorConfig.DockerConfig.RegistryCredential> registryCredentials;
    private final Set<String> pulledImages = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public ImageCacheService(DockerClient dockerClient, EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.registryCredentials = config.docker().registryCredentials();
    }

    public void ensureImageExists(String imageUri) {
        ensureImageExists(imageUri, null);
    }

    /**
     * Ensures {@code imageUri} is present locally, pulling it with the supplied credentials when
     * it is not. Behaves exactly like {@link #ensureImageExists(String)} — pull-once per process,
     * transient-failure retry, five-minute pull timeout — except that {@code auth}, when non-null,
     * replaces the credentials {@link #resolveAuth} would have derived from configuration.
     *
     * @param imageUri the image reference, for example {@code myregistry.example.com/app:1.0}
     * @param auth     registry credentials to use for this pull, or {@code null} to fall back to
     *                 the configured {@code floci-az.docker.registry-credentials}
     */
    public void ensureImageExists(String imageUri, AuthConfig auth) {
        if (pulledImages.contains(imageUri)) {
            return;
        }
        Object lock = locks.computeIfAbsent(imageUri, k -> new Object());
        synchronized (lock) {
            if (pulledImages.contains(imageUri)) {
                return;
            }
            if (isLocalImagePresent(imageUri)) {
                pulledImages.add(imageUri);
                LOG.infov("Image already present locally: {0}", imageUri);
                return;
            }
            LOG.infov("Pulling image: {0}", imageUri);
            try {
                runWithRetry(imageUri, MAX_PULL_ATTEMPTS, INITIAL_BACKOFF_MS,
                        () -> dockerClient.pullImageCmd(imageUri)
                                .withAuthConfig(resolveAuth(imageUri, auth))
                                .exec(new PullImageResultCallback())
                                .awaitCompletion(5, TimeUnit.MINUTES));
                pulledImages.add(imageUri);
                LOG.infov("Image pulled successfully: {0}", imageUri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image: " + imageUri, e);
            }
        }
    }

    /**
     * Credential-shaped overload of {@link #ensureImageExists(String, AuthConfig)}, so callers
     * outside {@code core/docker} can supply per-request registry credentials without depending
     * on the docker-java API. A null or blank {@code server} falls back to the configured
     * {@code floci-az.docker.registry-credentials}.
     */
    public void ensureImageExists(String imageUri, String server, String username, String password) {
        AuthConfig auth = server == null || server.isBlank() ? null : new AuthConfig()
                .withUsername(username)
                .withPassword(password)
                .withRegistryAddress(server);
        ensureImageExists(imageUri, auth);
    }

    /**
     * Runs the given pull attempt, retrying on transient registry failures with exponential
     * backoff. A failure is considered transient when either:
     * <ul>
     *   <li>the docker daemon throws {@link InternalServerErrorException} directly (HTTP 500,
     *       e.g. a registry's {@code "toomanyrequests: Rate exceeded"}), or</li>
     *   <li>{@link PullImageResultCallback#awaitCompletion} rewraps a daemon error as
     *       {@link DockerClientException} with a message starting with
     *       {@code "Could not pull image: "} (the async-callback path; same root cause, just
     *       a different exception class).</li>
     * </ul>
     * Permanent failures (auth, missing image, malformed request, or any other
     * {@code DockerClientException} not coming from the pull wrapper) keep surfacing their
     * original docker-java exception subclass on the first attempt and are not retried.
     */
    static void runWithRetry(String imageUri, int maxAttempts, long initialBackoffMs,
                             PullAttempt attempt) throws InterruptedException {
        long backoffMs = initialBackoffMs;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                attempt.run();
                return;
            } catch (RuntimeException e) {
                if (!isTransientPullFailure(e) || i == maxAttempts) {
                    throw e;
                }
                LOG.warnv(e, "Transient image pull failure for {0} (attempt {1}/{2}). "
                        + "Retrying in {3}ms.", imageUri, i, maxAttempts, backoffMs);
                Thread.sleep(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private static boolean isTransientPullFailure(RuntimeException e) {
        if (e instanceof InternalServerErrorException) {
            return true;
        }
        if (e instanceof DockerClientException && e.getMessage() != null
                && e.getMessage().startsWith("Could not pull image: ")) {
            return true;
        }
        return false;
    }

    @FunctionalInterface
    interface PullAttempt {
        void run() throws InterruptedException;
    }

    private boolean isLocalImagePresent(String imageUri) {
        try {
            dockerClient.inspectImageCmd(imageUri).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.debugv("Could not check local image presence for {0}: {1}", imageUri, e.getMessage());
            return false;
        }
    }

    /** A caller-supplied {@code AuthConfig} wins over the configured registry credentials. */
    private AuthConfig resolveAuth(String imageUri, AuthConfig supplied) {
        if (supplied != null) {
            LOG.debugv("Using request-supplied credentials for registry: {0}",
                    extractRegistryHost(imageUri));
            return supplied;
        }
        String host = extractRegistryHost(imageUri);
        for (EmulatorConfig.DockerConfig.RegistryCredential cred : registryCredentials) {
            if (cred.server().equals(host)) {
                LOG.debugv("Using configured credentials for registry: {0}", host);
                return new AuthConfig()
                        .withUsername(cred.username())
                        .withPassword(cred.password())
                        .withRegistryAddress(cred.server());
            }
        }
        return new AuthConfig();
    }

    /**
     * The registry host of an image reference: the first {@code /}-separated segment when it
     * contains a {@code .} or a {@code :}, otherwise the empty string (meaning Docker Hub).
     */
    public static String extractRegistryHost(String imageUri) {
        String firstSegment = imageUri.split("/")[0];
        return (firstSegment.contains(".") || firstSegment.contains(":")) ? firstSegment : "";
    }
}
