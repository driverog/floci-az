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

    /** Real Docker containers, with the reconciler ticking once per second. */
    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.container-instance.mocked", "false",
                    "floci-az.services.container-instance.reconcile-interval-seconds", "1");
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
