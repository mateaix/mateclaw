package vip.mate.skill.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillDependencyChecker.EndpointTarget;
import vip.mate.skill.runtime.SkillDependencyChecker.RequirementStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Endpoint-type requirement: TCP reachability probes surface
 * "service unreachable from this deployment" before an agent burns
 * rounds discovering it mid-task.
 */
class SkillDependencyCheckerEndpointTest {

    private SkillDependencyChecker checker;
    private ServerSocket listening;

    @BeforeEach
    void setUp() {
        // checkRequirement's ENDPOINT path never touches the mapper/registry.
        checker = new SkillDependencyChecker(null, null);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (listening != null && !listening.isClosed()) listening.close();
    }

    private int openLocalPort() throws IOException {
        listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        return listening.getLocalPort();
    }

    /** A loopback port that is (almost certainly) closed: bind, read, release. */
    private int closedLocalPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private SkillManifest.RequirementDef endpointReq(String check) {
        return SkillManifest.RequirementDef.builder()
                .key("meeting-api")
                .type("endpoint")
                .check(check)
                .build();
    }

    // ==================== target parsing ====================

    @Test
    void parsesUrlWithExplicitPort() {
        assertThat(SkillDependencyChecker.parseEndpointTarget("http://192.168.1.11:8181/DCMeeting/api/v2"))
                .isEqualTo(new EndpointTarget("192.168.1.11", 8181));
    }

    @Test
    void parsesUrlWithSchemeDefaultPorts() {
        assertThat(SkillDependencyChecker.parseEndpointTarget("https://meeting.example.com/api"))
                .isEqualTo(new EndpointTarget("meeting.example.com", 443));
        assertThat(SkillDependencyChecker.parseEndpointTarget("http://meeting.example.com"))
                .isEqualTo(new EndpointTarget("meeting.example.com", 80));
    }

    @Test
    void parsesHostPortAndBareHostShorthand() {
        assertThat(SkillDependencyChecker.parseEndpointTarget("192.168.1.11:8181"))
                .isEqualTo(new EndpointTarget("192.168.1.11", 8181));
        assertThat(SkillDependencyChecker.parseEndpointTarget("meeting.example.com"))
                .isEqualTo(new EndpointTarget("meeting.example.com", 80));
    }

    @Test
    void unparseableTargetsReturnNull() {
        assertThat(SkillDependencyChecker.parseEndpointTarget(null)).isNull();
        assertThat(SkillDependencyChecker.parseEndpointTarget("  ")).isNull();
        assertThat(SkillDependencyChecker.parseEndpointTarget("host:notaport")).isNull();
        assertThat(SkillDependencyChecker.parseEndpointTarget("http://")).isNull();
    }

    // ==================== requirement probing ====================

    @Test
    void reachableEndpointIsSatisfied() throws IOException {
        int port = openLocalPort();
        RequirementStatus st = checker.checkRequirement(
                endpointReq("http://127.0.0.1:" + port + "/api/v2"));
        assertThat(st).isEqualTo(RequirementStatus.SATISFIED);
    }

    @Test
    void unreachableEndpointIsMissing() throws IOException {
        int port = closedLocalPort();
        RequirementStatus st = checker.checkRequirement(
                endpointReq("http://127.0.0.1:" + port));
        assertThat(st).isEqualTo(RequirementStatus.MISSING);
    }

    @Test
    void unparseableEndpointIsUnknownNotMissing() {
        // A misdeclared manifest must not flip the skill to setup-needed.
        assertThat(checker.checkRequirement(endpointReq("http://")))
                .isEqualTo(RequirementStatus.UNKNOWN);
    }

    @Test
    void urlShapedCheckInfersEndpointTypeWithoutDeclaredType() throws IOException {
        int port = closedLocalPort();
        SkillManifest.RequirementDef req = SkillManifest.RequirementDef.builder()
                .key("meeting-api")
                .check("http://127.0.0.1:" + port)
                .build();
        // ANY would yield UNKNOWN; the URL-shaped check must probe and miss.
        assertThat(checker.checkRequirement(req)).isEqualTo(RequirementStatus.MISSING);
    }

    @Test
    void probeResultIsCachedWithinTtl() throws IOException {
        int port = openLocalPort();
        SkillManifest.RequirementDef req = endpointReq("http://127.0.0.1:" + port);
        assertThat(checker.checkRequirement(req)).isEqualTo(RequirementStatus.SATISFIED);
        listening.close();
        // Served from the 60s cache — no fresh probe against the closed port.
        assertThat(checker.checkRequirement(req)).isEqualTo(RequirementStatus.SATISFIED);
    }

    // ==================== feature gate integration ====================

    @Test
    void featureRequiringUnreachableEndpointIsSetupNeeded() throws IOException {
        int port = closedLocalPort();
        SkillManifest.RequirementDef req = endpointReq("http://127.0.0.1:" + port);
        SkillManifest.FeatureDef feature = SkillManifest.FeatureDef.builder()
                .id("default")
                .requires(List.of("meeting-api"))
                .fallbackMessage("会议系统服务不可达，请检查网络/VPN")
                .build();

        var result = checker.checkFeature(feature, Map.of("meeting-api", req));

        assertThat(result.getStatus()).isEqualTo("SETUP_NEEDED");
        assertThat(result.getMissing()).containsExactly("meeting-api");
        assertThat(result.getReason()).contains("不可达");
    }
}
