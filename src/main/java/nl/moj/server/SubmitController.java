package nl.moj.server;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ArrayNode;
import nl.moj.server.competition.Competition;
import nl.moj.server.competition.ScoreService;
import nl.moj.server.compile.CompileService;
import nl.moj.server.test.TestResult;
import nl.moj.server.test.TestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Controller
@MessageMapping("/submit")
public class SubmitController {
	private static final Logger log = LoggerFactory.getLogger(SubmitController.class);

	@Autowired
	private CompileService compileService;

	@Autowired
	private TestService testService;

	@Autowired
	@Qualifier("compiling")
	private Executor compiling;

	@Autowired
	@Qualifier("testing")
	private Executor testing;

	@Autowired
	private SimpMessagingTemplate template;

	@Autowired
	private ScoreService scoreService;

	@Value("${moj.server.timeout}")
	private int TIMEOUT;

	@Autowired
	private Competition competition;


	@MessageMapping("/compile")
	public void compile(SourceMessage message, @AuthenticationPrincipal Principal user, MessageHeaders mesg)
			throws Exception {
		log.info("Compile job submitted for: {}", user.getName());
		message.setTeam(user.getName());
		CompletableFuture.supplyAsync(compileService.compile(message), compiling)
				.orTimeout(TIMEOUT, TimeUnit.SECONDS).thenAccept(compileResult -> log.debug(compileResult.getCompileResult()));
	}

	@MessageMapping("/test")
	public void test(SourceMessage message, @AuthenticationPrincipal Principal user, MessageHeaders mesg)
			throws Exception {
		message.setTeam(user.getName());
		CompletableFuture.supplyAsync(compileService.compileWithTest(message), testing)
				.orTimeout(TIMEOUT, TimeUnit.SECONDS)
				.thenComposeAsync(compileResult -> testService.testAll(compileResult), testing);
	}

	/**
	 * Submits the final solution of the team and closes the assignment for the submitting team.
	 * The submitting team cannot work with the assignment after closing.
	 *
	 * @param message
	 * @param user
	 * @param mesg
	 * @throws Exception
	 */
	@MessageMapping("/submit")
	public void submit(SourceMessage message, @AuthenticationPrincipal Principal user, MessageHeaders mesg)
			throws Exception {
		if( !competition.getCurrentAssignment().isTeamFinished(user.getName()) ) {
			int scoreAtSubmissionTime = competition.getRemainingTime();
			message.setTeam(user.getName());
			CompletableFuture.supplyAsync(compileService.compileForSubmit(message), testing)
					.orTimeout(TIMEOUT, TimeUnit.SECONDS)
					.thenComposeAsync(compileResult -> testService.testSubmit(compileResult), testing)
					.thenAccept(testResult -> {
						setFinalAssignmentScore(testResult, scoreAtSubmissionTime);
					});
		}
	}

	private void setFinalAssignmentScore(TestResult testResult, int scoreAtSubmissionTime) {
		if (testResult.isSuccessful()) {
			scoreService.registerScoreAtSubmission(testResult.getUser(), scoreAtSubmissionTime);
			template.convertAndSend("/queue/rankings", "refresh");
		}
	}

	@JsonDeserialize(using = SourceMessageDeserializer.class)
	public static class SourceMessage {

		private String team;
		private Map<String, String> source;
		private List<String> tests;

		public SourceMessage(String team, Map<String, String> source, List<String> tests) {
			this.team = team;
			this.source = source;
			this.tests = tests;
		}

		public SourceMessage(Map<String, String> source, List<String> tests) {
			this.source = source;
			this.tests = tests;
		}

		public String getTeam() {
			return team;
		}

		public void setTeam(String team) {
			this.team = team;
		}

		public Map<String, String> getSource() {
			return source;
		}

		public void setSource(Map<String, String> source) {
			this.source = source;
		}

		public List<String> getTests() {
			return tests;
		}

		public void setTests(List<String> tests) {
			this.tests = tests;
		}
	}


	private class SourceMessageDeserializer extends JsonDeserializer<SourceMessage> {
		@Override
		public SourceMessage deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
				throws IOException {
			JsonNode node = jsonParser.getCodec().readTree(jsonParser);
			Map<String, String> sources = new HashMap<>();
			if (node.get("sources") != null && node.get("sources").isArray()) {
				ArrayNode sourceArray = (ArrayNode) node.get("sources");
				for (int i = 0; i < sourceArray.size(); i++) {
					JsonNode sourceElement = sourceArray.get(i);
					sources.put(sourceElement.get("filename").textValue(), sourceElement.get("content").textValue());
				}
			}
			List<String> tests = new ArrayList<>();
			if (node.get("tests") != null && node.get("tests").isArray()) {
				ArrayNode jsonTests = (ArrayNode) node.get("tests");
				jsonTests.forEach(t -> tests.add(t.asText()));
			}
			return new SourceMessage(sources, tests);
		}
	}
}
