package io.floci.az.services.containerinstance;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Config overrides shared by the container-instance test classes. */
public final class ContainerInstanceProfiles {

    /** No Docker call at any point; pure ARM state. */
    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.container-instance.mocked", "true");
        }
    }

    /**
     * Real Docker containers, with the reconciler ticking once per second.
     *
     * <p>The stop grace period is shortened from the shipped 10 seconds: the test fixtures are
     * {@code sh -c 'while true; …'} loops that ignore SIGTERM, so a group of three containers
     * would spend 30 seconds in {@code docker stop} and exceed the HTTP client's read timeout.</p>
     */
    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.container-instance.mocked", "false",
                    "floci-az.services.container-instance.reconcile-interval-seconds", "1",
                    "floci-az.services.container-instance.stop-timeout-seconds", "2");
        }
    }

    /** The provider is not served; ARM paths fall through to the generic handler. */
    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.container-instance.enabled", "false");
        }
    }

    private ContainerInstanceProfiles() {
    }
}
