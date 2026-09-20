package vip.mate.goal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.service.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

/** Child-JVM fixture for changing host timezone across a real process restart. */
public class GoalJsonTimezoneProcessProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var application = new SpringApplication(vip.mate.MateClawApplication.class, GoalJsonRestartIntegrationTest.MemoryFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run(
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("timezone") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "--spring.ai.dashscope.api-key=timezone-fixture-no-provider",
                "--mateclaw.goal.enabled=false", "--mateclaw.plugin.enabled=false",
                "--mateclaw.skill.workspace.auto-init=false", "--mateclaw.skill.workspace.root=" + directory.resolve("skills"))) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var goals = context.getBean(GoalService.class);
            var requirements = context.getBean(GoalJsonAcceptanceService.class);
            var artifacts = context.getBean(ManagedGoalJsonService.class);
            var bindings = context.getBean(GoalJsonBindingService.class);
            Properties receipt = new Properties();
            Path file = directory.resolve("receipt.properties");
            if (args[1].equals("write")) {
                jdbc.update("INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted) VALUES (99001,'timezone-owner','unused',TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
                for (String name : List.of("valid", "expired")) {
                    String conversation = "timezone-" + name;
                    jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted) VALUES (?,?,'timezone-owner',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", name.equals("valid") ? 99002L : 99003L, conversation);
                    var request = new GoalCreateRequest(); request.setConversationId(conversation);
                    request.setWorkspaceId(1L); request.setAgentId(1L); request.setTitle(name); request.setDescription("Timezone restart fixture");
                    request.setPersistentExecution(false); request.setAutoFollowupEnabled(false);
                    long goal = goals.create(request, "timezone-owner").getId();
                    requirements.configure(goal, "r", new GoalJsonAcceptanceService.ConfigureRequest(0L, "report", List.of("summary")), "timezone-owner");
                    var version = artifacts.publish(goal, "report", new ManagedGoalJsonService.PublishRequest(0L, "{\"summary\":false}"), "timezone-owner");
                    bindings.check(goal, "r", new GoalJsonBindingService.CheckRequest(1L, version.artifactId(), 1L), "timezone-owner");
                    receipt.setProperty(name + ".goal", String.valueOf(goal));
                    receipt.setProperty(name + ".artifact", version.artifactId());
                    receipt.setProperty(name + ".created", String.valueOf(version.createdAt().getEpochSecond()));
                    receipt.setProperty(name + ".expires", String.valueOf(version.expiresAt().getEpochSecond()));
                    if (name.equals("expired")) {
                        Timestamp expired = Timestamp.from(Instant.now().minusSeconds(60));
                        jdbc.update("UPDATE mate_goal_json_artifact SET expires_at=?,expires_epoch_second=? WHERE goal_id=?", expired, expired.toInstant().getEpochSecond(), goal);
                        jdbc.update("UPDATE mate_goal_json_binding SET expires_at=?,expires_epoch_second=? WHERE goal_id=?", expired, expired.toInstant().getEpochSecond(), goal);
                        if (!bindings.state(goal, "timezone-owner").getFirst().status().equals("EXPIRED")) throw new AssertionError("Expiry fixture must initially be expired");
                    }
                }
                jdbc.update("INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted) VALUES (99004,'timezone-lease','timezone-owner',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
                var request = new GoalCreateRequest(); request.setConversationId("timezone-lease");
                request.setWorkspaceId(1L); request.setAgentId(1L); request.setTitle("lease"); request.setDescription("Owner restart fixture");
                request.setPersistentExecution(true); request.setAutoFollowupEnabled(true);
                var goal = goals.create(request, "timezone-owner");
                requirements.configure(goal.getId(), "r", new GoalJsonAcceptanceService.ConfigureRequest(0L, "report", List.of("summary")), "timezone-owner");
                var continuations = context.getBean(GoalContinuationStore.class);
                var coordinator = context.getBean(GoalRunCoordinator.class);
                var now = java.time.LocalDateTime.now();
                continuations.discover(now);
                var run = coordinator.claim(continuations.get(goal.getId()), goals.getById(goal.getId()), now);
                if (run == null || !coordinator.markRunning(run, now)) throw new AssertionError("Owner fixture failed to claim");
                jdbc.update("UPDATE mate_goal_attempt SET lease_until=?,lease_until_epoch_second=? WHERE goal_id=?", now.minusSeconds(60), Instant.now().minusSeconds(60).getEpochSecond(), goal.getId());
                jdbc.update("UPDATE mate_goal_continuation SET lease_until=?,lease_until_epoch_second=? WHERE goal_id=?", now.minusSeconds(60), Instant.now().minusSeconds(60).getEpochSecond(), goal.getId());
                receipt.setProperty("owner.goal", String.valueOf(goal.getId()));
                receipt.setProperty("owner.attempt", run.attempt().id());
                receipt.setProperty("owner.token", run.attempt().leaseToken());
                receipt.setProperty("owner.revision", String.valueOf(run.revision()));
                try (var output = Files.newOutputStream(file)) { receipt.store(output, "Disposable timezone fixture"); }
            } else {
                try (var input = Files.newInputStream(file)) { receipt.load(input); }
                long ownerGoal = Long.parseLong(receipt.getProperty("owner.goal"));
                var origin = vip.mate.agent.context.ChatOrigin.web("timezone-lease", "timezone-owner", 1L, null).withAgent(1L)
                        .withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(ownerGoal,
                                receipt.getProperty("owner.attempt"), null, null, receipt.getProperty("owner.token")));
                boolean rejected = false;
                try { artifacts.publishForRuntime(origin, "report", new ManagedGoalJsonService.PublishRequest(0L, "{}")); }
                catch (vip.mate.exception.MateClawException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("Expired scheduler owner regained JSON publication after timezone change");
                var continuations = context.getBean(GoalContinuationStore.class);
                var coordinator = context.getBean(GoalRunCoordinator.class);
                var now = java.time.LocalDateTime.now();
                var oldRun = new GoalRunCoordinator.ClaimedRun(continuations.get(ownerGoal), goals.getById(ownerGoal),
                        context.getBean(GoalAttemptStore.class).get(receipt.getProperty("owner.attempt")),
                        Long.parseLong(receipt.getProperty("owner.revision")));
                if (coordinator.renew(oldRun, now)) throw new AssertionError("Expired owner renewed after timezone change");
                if (context.getBean(GoalRecoveryService.class).recoverExpired(Instant.now()) != 1) throw new AssertionError("Expired owner was not recovered");
                var fresh = coordinator.claim(continuations.get(ownerGoal), goals.getById(ownerGoal), now);
                if (fresh == null || !coordinator.markRunning(fresh, now)) throw new AssertionError("Recovery failed to claim a fresh owner");
                var freshOrigin = origin.withExecutionAttribution(new vip.mate.agent.context.ExecutionAttribution(ownerGoal,
                        fresh.attempt().id(), null, null, fresh.attempt().leaseToken()));
                if (artifacts.publishForRuntime(freshOrigin, "report", new ManagedGoalJsonService.PublishRequest(0L, "{}"))
                        .generation() != 1) throw new AssertionError("Fresh owner cannot publish after recovery");
                long expiredGoal = Long.parseLong(receipt.getProperty("expired.goal"));
                if (bindings.state(expiredGoal, "timezone-owner").getFirst().acceptanceEligible()) {
                    throw new AssertionError("Previously expired JSON became eligible after host timezone changed");
                }
                long validGoal = Long.parseLong(receipt.getProperty("valid.goal"));
                var version = artifacts.read(validGoal, receipt.getProperty("valid.artifact"), "timezone-owner").artifact();
                if (version.createdAt().getEpochSecond() != Long.parseLong(receipt.getProperty("valid.created"))
                        || version.expiresAt().getEpochSecond() != Long.parseLong(receipt.getProperty("valid.expires"))) {
                    throw new AssertionError("Managed JSON absolute timestamps changed across process restart");
                }
                if (!bindings.state(validGoal, "timezone-owner").getFirst().acceptanceEligible()) throw new AssertionError("Valid binding lost after timezone change");
            }
        }
    }
}
