package io.floci.az.services.containerinstance;

import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerGroup;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerRecord;
import io.floci.az.services.containerinstance.ContainerInstanceModels.ContainerStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.GroupStateValue;
import io.floci.az.services.containerinstance.ContainerInstanceModels.RestartPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic coverage of {@link ContainerGroupReconciler#computeGroupState}, the pure
 * function behind group transitions G2, G6, G7 and G8.
 *
 * <p>{@code ContainerInstanceDockerTest} exercises the same transitions end to end, but it
 * samples {@code instanceView.state} at whatever instant a real container happens to be in, so
 * a policy that is wrong only while a restart is pending can pass there by luck. That is
 * exactly how the OnFailure defect below reached a green run: with
 * {@code restartPolicy: OnFailure} and a nonzero exit, the group was reported {@code Failed}
 * whenever the sample landed between the container exiting and the reconciler restarting it,
 * instead of {@code Running}. These cases pin every policy against every terminal shape with
 * no timing involved.</p>
 */
@DisplayName("ContainerGroupReconciler — group state machine (G2, G6, G7, G8) and reconcilability")
class ContainerGroupStateTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ContainerRecord app(String name, ContainerStateValue state, Integer exitCode) {
        ContainerRecord record = new ContainerRecord();
        record.setName(name);
        record.setState(state);
        record.setExitCode(exitCode);
        return record;
    }

    private static ContainerRecord init(String name, ContainerStateValue state, Integer exitCode) {
        ContainerRecord record = app(name, state, exitCode);
        record.setInit(true);
        return record;
    }

    private static ContainerGroup group(RestartPolicy policy, ContainerRecord... containers) {
        ContainerGroup group = new ContainerGroup();
        group.setName("demo-group");
        group.setRestartPolicy(policy);
        group.setContainers(List.of(containers));
        return group;
    }

    private static GroupStateValue state(RestartPolicy policy, ContainerRecord... containers) {
        return ContainerGroupReconciler.computeGroupState(group(policy, containers));
    }

    // ── G2: anything still running keeps the group Running ─────────────────────

    @Nested
    @DisplayName("G2 — a running container keeps the group Running")
    class AnyRunning {

        @Test
        @DisplayName("single Running container, every policy")
        void singleRunningContainer() {
            for (RestartPolicy policy : RestartPolicy.values()) {
                assertEquals(GroupStateValue.RUNNING,
                        state(policy, app("web", ContainerStateValue.RUNNING, null)),
                        "policy " + policy);
            }
        }

        @Test
        @DisplayName("one Running alongside one Terminated nonzero, every policy")
        void mixedRunningAndTerminated() {
            for (RestartPolicy policy : RestartPolicy.values()) {
                assertEquals(GroupStateValue.RUNNING,
                        state(policy,
                                app("web", ContainerStateValue.RUNNING, null),
                                app("sidecar", ContainerStateValue.TERMINATED, 1)),
                        "policy " + policy);
            }
        }

        @Test
        @DisplayName("a Waiting container is a pending restart, not a terminal state")
        void waitingIsNotTerminal() {
            for (RestartPolicy policy : RestartPolicy.values()) {
                assertEquals(GroupStateValue.RUNNING,
                        state(policy, app("web", ContainerStateValue.WAITING, null)),
                        "policy " + policy);
            }
        }
    }

    // ── G6 / G7 / G8: every policy against every terminal shape ────────────────

    @Nested
    @DisplayName("Always — never terminal")
    class Always {

        @Test
        @DisplayName("all Terminated with exit 0 is still Running")
        void cleanExit() {
            assertEquals(GroupStateValue.RUNNING,
                    state(RestartPolicy.ALWAYS, app("task", ContainerStateValue.TERMINATED, 0)));
        }

        @Test
        @DisplayName("all Terminated with a nonzero exit is still Running")
        void nonZeroExit() {
            assertEquals(GroupStateValue.RUNNING,
                    state(RestartPolicy.ALWAYS, app("task", ContainerStateValue.TERMINATED, 7)));
        }
    }

    @Nested
    @DisplayName("OnFailure — Succeeded on a clean exit, Running while a restart is pending")
    class OnFailure {

        @Test
        @DisplayName("G6: all Terminated with exit 0 is Succeeded")
        void cleanExitSucceeds() {
            assertEquals(GroupStateValue.SUCCEEDED,
                    state(RestartPolicy.ON_FAILURE, app("task", ContainerStateValue.TERMINATED, 0)));
        }

        @Test
        @DisplayName("G8: a nonzero exit is Running, because OnFailure restarts it")
        void nonZeroExitStaysRunning() {
            assertEquals(GroupStateValue.RUNNING,
                    state(RestartPolicy.ON_FAILURE, app("task", ContainerStateValue.TERMINATED, 7)));
        }

        @Test
        @DisplayName("G8: one clean and one failed exit is Running")
        void mixedExitCodesStayRunning() {
            assertEquals(GroupStateValue.RUNNING,
                    state(RestartPolicy.ON_FAILURE,
                            app("ok", ContainerStateValue.TERMINATED, 0),
                            app("bad", ContainerStateValue.TERMINATED, 3)));
        }

        @Test
        @DisplayName("G6: several containers all exiting 0 is Succeeded")
        void allCleanExitsSucceed() {
            assertEquals(GroupStateValue.SUCCEEDED,
                    state(RestartPolicy.ON_FAILURE,
                            app("a", ContainerStateValue.TERMINATED, 0),
                            app("b", ContainerStateValue.TERMINATED, 0)));
        }
    }

    @Nested
    @DisplayName("Never — the only policy under which a nonzero exit settles into Failed")
    class Never {

        @Test
        @DisplayName("G6: all Terminated with exit 0 is Succeeded")
        void cleanExitSucceeds() {
            assertEquals(GroupStateValue.SUCCEEDED,
                    state(RestartPolicy.NEVER, app("task", ContainerStateValue.TERMINATED, 0)));
        }

        @Test
        @DisplayName("G7: a nonzero exit is Failed")
        void nonZeroExitFails() {
            assertEquals(GroupStateValue.FAILED,
                    state(RestartPolicy.NEVER, app("task", ContainerStateValue.TERMINATED, 9)));
        }

        @Test
        @DisplayName("G7: one clean and one failed exit is Failed")
        void anyNonZeroFails() {
            assertEquals(GroupStateValue.FAILED,
                    state(RestartPolicy.NEVER,
                            app("ok", ContainerStateValue.TERMINATED, 0),
                            app("bad", ContainerStateValue.TERMINATED, 1)));
        }
    }

    // ── init containers are excluded from the group's terminal state ───────────

    @Nested
    @DisplayName("Init containers do not decide the group state")
    class InitContainers {

        @Test
        @DisplayName("an exited init container alongside a running app container is Running")
        void exitedInitWithRunningApp() {
            assertEquals(GroupStateValue.RUNNING,
                    state(RestartPolicy.NEVER,
                            init("setup", ContainerStateValue.TERMINATED, 0),
                            app("web", ContainerStateValue.RUNNING, null)));
        }

        @Test
        @DisplayName("a nonzero init exit does not by itself fail a Never group whose app exited 0")
        void initExitCodeIgnored() {
            assertEquals(GroupStateValue.SUCCEEDED,
                    state(RestartPolicy.NEVER,
                            init("setup", ContainerStateValue.TERMINATED, 5),
                            app("task", ContainerStateValue.TERMINATED, 0)));
        }

        @Test
        @DisplayName("a group of only init containers is Running")
        void onlyInitContainers() {
            assertEquals(GroupStateValue.RUNNING,
                    state(RestartPolicy.NEVER, init("setup", ContainerStateValue.TERMINATED, 0)));
        }
    }

    @Test
    @DisplayName("a group with no containers at all is Running")
    void emptyGroupIsRunning() {
        assertEquals(GroupStateValue.RUNNING, state(RestartPolicy.ALWAYS));
    }

    @Test
    @DisplayName("a Terminated container with no recorded exit code is treated as a clean exit")
    void missingExitCodeIsNotAFailure() {
        assertEquals(GroupStateValue.SUCCEEDED,
                state(RestartPolicy.NEVER, app("task", ContainerStateValue.TERMINATED, null)));
    }

    // ── which groups the reconciler owns at all ────────────────────────────────

    /**
     * {@link ContainerGroupReconciler#isReconcilable} decides whether a tick touches a group.
     *
     * <p>The {@code Failed} case is the one worth pinning: a group whose {@code PUT} failed has
     * been rolled back — every container id nulled, every host port released — so a tick that
     * reconciled it read the absent infra container as "the namespace owner died" and repaired
     * it, resurrecting a deployment the client was told had failed and re-binding ports the
     * allocator had already handed out. The end state was a group reporting {@code Running}
     * under a {@code provisioningState} of {@code Failed}, with an orphan infra container
     * holding a port for the life of the process.</p>
     */
    @Nested
    @DisplayName("isReconcilable — which groups a tick owns")
    class Reconcilable {

        private ContainerGroup group(String provisioningState, GroupStateValue groupState,
                                     boolean degraded) {
            ContainerGroup group = new ContainerGroup();
            group.setName("demo-group");
            group.setRestartPolicy(RestartPolicy.ALWAYS);
            group.setContainers(List.of());
            group.setProvisioningState(provisioningState);
            group.setGroupState(groupState);
            group.setDegraded(degraded);
            return group;
        }

        @Test
        @DisplayName("a provisioned, running group is reconciled")
        void succeededRunningIsReconciled() {
            assertTrue(ContainerGroupReconciler.isReconcilable(
                    group("Succeeded", GroupStateValue.RUNNING, false)));
        }

        @Test
        @DisplayName("a group whose provisioning failed is left alone")
        void failedProvisioningIsSkipped() {
            assertFalse(ContainerGroupReconciler.isReconcilable(
                    group("Failed", GroupStateValue.FAILED, false)));
        }

        @Test
        @DisplayName("a rolled-back group that still reports Running is left alone")
        void failedProvisioningWithRunningStateIsSkipped() {
            assertFalse(ContainerGroupReconciler.isReconcilable(
                    group("Failed", GroupStateValue.RUNNING, false)));
        }

        @Test
        @DisplayName("a group with no provisioning state recorded is left alone")
        void absentProvisioningStateIsSkipped() {
            assertFalse(ContainerGroupReconciler.isReconcilable(
                    group(null, GroupStateValue.RUNNING, false)));
        }

        @Test
        @DisplayName("a deliberately stopped group is left alone")
        void stoppedIsSkipped() {
            assertFalse(ContainerGroupReconciler.isReconcilable(
                    group("Succeeded", GroupStateValue.STOPPED, false)));
        }

        @Test
        @DisplayName("a degraded group has no containers to own")
        void degradedIsSkipped() {
            assertFalse(ContainerGroupReconciler.isReconcilable(
                    group("Succeeded", GroupStateValue.RUNNING, true)));
        }
    }
}
