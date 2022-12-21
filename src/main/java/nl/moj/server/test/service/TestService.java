/*
   Copyright 2020 First Eight BV (The Netherlands)
 

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file / these files except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.moj.server.test.service;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.zeroturnaround.exec.ProcessExecutor;

import nl.moj.server.assignment.descriptor.AssignmentDescriptor;
import nl.moj.server.config.properties.MojServerProperties;
import nl.moj.server.runtime.CompetitionRuntime;
import nl.moj.server.runtime.model.ActiveAssignment;
import nl.moj.server.runtime.model.AssignmentFile;
import nl.moj.server.runtime.model.AssignmentStatus;
import nl.moj.server.runtime.repository.AssignmentStatusRepository;
import nl.moj.server.teams.model.Team;
import nl.moj.server.teams.service.TeamService;
import nl.moj.server.test.model.TestAttempt;
import nl.moj.server.test.model.TestCase;
import nl.moj.server.test.repository.TestAttemptRepository;
import nl.moj.server.test.repository.TestCaseRepository;
import nl.moj.server.util.CompletableFutures;
import nl.moj.server.util.LengthLimitedOutputCatcher;

@Service
public class TestService {
	public static final String SECURITY_POLICY_FOR_UNIT_TESTS = "securityPolicyForUnitTests.policy";
	private static final Logger log = LoggerFactory.getLogger(TestService.class);
	private static final Pattern JUNIT_PREFIX_P = Pattern.compile("^(JUnit version 4.12)?\\s*\\.?",
			Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private final MojServerProperties mojServerProperties;

	private final TestCaseRepository testCaseRepository;

	private final TestAttemptRepository testAttemptRepository;

	private final AssignmentStatusRepository assignmentStatusRepository;

	private final TeamService teamService;

	public TestService(MojServerProperties mojServerProperties, CompetitionRuntime competition,
			TestCaseRepository testCaseRepository, TestAttemptRepository testAttemptRepository,
			AssignmentStatusRepository assignmentStatusRepository, TeamService teamService) {
		this.mojServerProperties = mojServerProperties;
		this.testCaseRepository = testCaseRepository;
		this.testAttemptRepository = testAttemptRepository;
		this.assignmentStatusRepository = assignmentStatusRepository;
		this.teamService = teamService;
	}

	private CompletableFuture<TestResult> scheduleTest(Team team, TestAttempt testAttempt, AssignmentFile test,
			Executor executor, ActiveAssignment activeAssignment) {
		Assert.isTrue(activeAssignment.getCompetitionSession() != null, "CompetitionSession is not ready");
		return CompletableFuture.supplyAsync(() -> executeTest(team, testAttempt, test, activeAssignment), executor);
	}

	public CompletableFuture<TestResults> scheduleTests(TestRequest testRequest, List<AssignmentFile> tests,
			Executor executor, ActiveAssignment activeAssignment) {
		log.info("activeAssignment: " + activeAssignment + " tests: " + tests.size());
		List<CompletableFuture<TestResult>> testFutures = new ArrayList<>();

		TestAttempt ta = null;
		try {
			AssignmentStatus optionalStatus = assignmentStatusRepository.findByAssignmentAndCompetitionSessionAndTeam(
					activeAssignment.getAssignment(), activeAssignment.getCompetitionSession(), testRequest.getTeam());
			ta = registerIfNeeded(TestAttempt.builder().assignmentStatus(optionalStatus).dateTimeStart(Instant.now())
					.uuid(UUID.randomUUID()).build());
			log.info("ta.id " + ta.getId());
			Assert.isTrue(activeAssignment.getCompetitionSession() != null, "CompetitionSession is not ready");
			log.info("testFutures " + testFutures.size());
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new RuntimeException(ex);
		}
		final TestAttempt testAttempt = ta;
		tests.forEach(
				t -> testFutures.add(scheduleTest(testRequest.getTeam(), testAttempt, t, executor, activeAssignment)));

		return CompletableFutures.allOf(testFutures).thenApply(r -> {
			testAttempt.setDateTimeEnd(Instant.now());
			TestAttempt updatedTa = registerIfNeeded(testAttempt);
			return TestResults.builder().dateTimeStart(updatedTa.getDateTimeStart())
					.dateTimeEnd(updatedTa.getDateTimeEnd()).testAttemptUuid(updatedTa.getUuid()).results(r).build();
		});
	}

	private TestAttempt registerIfNeeded(TestAttempt ta) {
		TestAttempt result = ta;
		if (result.getAssignmentStatus() != null) {
			result = testAttemptRepository.save(ta);
		}
		return result;
	}

	private TestResult executeTest(Team team, TestAttempt testAttempt, AssignmentFile file,
			ActiveAssignment activeAssignment) {
		Assert.isTrue(activeAssignment.getCompetitionSession() != null, "CompetitionSession is not ready");
		Assert.isTrue(activeAssignment.getAssignment().getName() != null, "assignmentcontext is not ready");
		TestCase testCase = TestCase.builder().testAttempt(testAttempt).name(file.getName()).uuid(UUID.randomUUID())
				.dateTimeStart(Instant.now()).build();

		AssignmentDescriptor ad = activeAssignment.getAssignmentDescriptor();
		Path teamAssignmentDir = teamService.getTeamAssignmentDirectory(activeAssignment.getCompetitionSession().getUuid(),
				team.getUuid(), activeAssignment.getAssignment().getName());

		Path policy = ad.getAssignmentFiles().getSecurityPolicy();
		if (policy != null) {
			policy = ad.getDirectory().resolve(policy);
		} else {
			policy = mojServerProperties.getDirectories().getBaseDirectory()
					.resolve(mojServerProperties.getDirectories().getLibDirectory())
					.resolve(SECURITY_POLICY_FOR_UNIT_TESTS);
		}

		Duration timeout = ad.getTestTimeout() != null ? ad.getTestTimeout()
				: mojServerProperties.getLimits().getTestTimeout();

		if (!policy.toFile().exists()) {
			log.error(
					"No security policy other than default JVM version installed, refusing to execute tests. Please configure a default security policy.");
			throw new RuntimeException("security policy file not found");
		}
		log.info("starting commandExecutor {} ", teamAssignmentDir);

		try (final LengthLimitedOutputCatcher jUnitOutput = new LengthLimitedOutputCatcher(
				mojServerProperties.getLimits().getTestOutputLimits());
				final LengthLimitedOutputCatcher jUnitError = new LengthLimitedOutputCatcher(
						mojServerProperties.getLimits().getTestOutputLimits())) {
			boolean isTimeout = false;
			int exitvalue = 0;

			try {
				List<String> commandParts = new ArrayList<>();

				commandParts.add(
						mojServerProperties.getLanguages().getJavaVersion(ad.getJavaVersion()).getRuntime().toString());
				if (ad.getJavaVersion() >= 12) {
					commandParts.add("--enable-preview");
				}
				commandParts.add("-cp");
				commandParts.add(makeClasspath(teamAssignmentDir));
				commandParts.add("-Djava.security.manager");
				commandParts.add("-Djava.security.policy=" + policy.toAbsolutePath());
				commandParts.add("org.junit.runner.JUnitCore");
				commandParts.add(file.getName());

				final ProcessExecutor commandExecutor = new ProcessExecutor().command(commandParts);
				log.debug("Executing command {}", commandExecutor.getCommand().toString().replaceAll(",", "\n"));
				exitvalue = commandExecutor.directory(teamAssignmentDir.toFile())
						.timeout(timeout.toSeconds(), TimeUnit.SECONDS).redirectOutput(jUnitOutput)
						.redirectError(jUnitError).execute().getExitValue();
			} catch (TimeoutException e) {
				// process is automatically destroyed
				log.debug("Unit test for {} timed out and got killed", team.getName());
				isTimeout = true;
			} catch (SecurityException se) {
				log.debug("Unit test for {} got security error", team.getName());
				log.error(se.getMessage(), se);
			}
			if (isTimeout) {
				jUnitOutput.getBuffer().append('\n')
						.append(mojServerProperties.getLimits().getTestOutputLimits().getTimeoutMessage());
			}

			final boolean success;
			final String result;
			if (jUnitOutput.length() > 0) {
				stripJUnitPrefix(jUnitOutput.getBuffer());
				// if we still have some output left and exitvalue = 0
				success = jUnitOutput.length() > 0 && exitvalue == 0 && !isTimeout;
				result = jUnitOutput.toString();
				if (jUnitOutput.length() == 0) {
					log.info("zero normal junit output, error output: {}-{}", jUnitOutput.toString(),
							jUnitError.toString());
				}
			} else {
				log.info("zero normal junit output, error output: {}", jUnitError.toString());
				result = jUnitError.toString();
				success = (exitvalue == 0) && !isTimeout;
			}
			log.info("finished unit test: {}, exitvalue: {}, outputlength: {}, isTimeout: {} ", file.getName(),
					exitvalue, result.length(), isTimeout);

			testCase = testCase.toBuilder().success(success).timeout(isTimeout).testOutput(result)
					.dateTimeEnd(Instant.now()).build();
			if (testAttempt.getAssignmentStatus() != null) {
				testCase = testCaseRepository.save(testCase);
			}
			testAttempt.getTestCases().add(testCase);

			return TestResult.builder().testCaseUuid(testCase.getUuid()).dateTimeStart(testCase.getDateTimeStart())
					.dateTimeEnd(testCase.getDateTimeEnd()).success(testCase.isSuccess()).timeout(testCase.isTimeout())
					.testName(testCase.getName()).testOutput(testCase.getTestOutput()).build();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}

		// TODO this should not be a null result.
		return null;
	}

	private void stripJUnitPrefix(StringBuilder result) {
		final Matcher matcher = JUNIT_PREFIX_P.matcher(result);
		if (matcher.find()) {
			log.trace("stripped '{}'", matcher.group());
			result.delete(0, matcher.end());
			if (result.length() > 0 && result.charAt(0) == '\n') {
				result.deleteCharAt(0);
			}
		} else {
			log.trace("stripped nothing of '{}'", result.subSequence(0, 50));
		}
	}

	private String makeClasspath(Path teamAssignmentDir) {
		File classesDir = FileUtils.getFile(teamAssignmentDir.toFile(), "classes");
		final List<File> classPath = new ArrayList<>();
		classPath.add(classesDir);
		classPath.add(FileUtils.getFile(mojServerProperties.getDirectories().getBaseDirectory().toFile(),
				mojServerProperties.getDirectories().getLibDirectory(), "junit-4.12.jar"));
		classPath.add(FileUtils.getFile(mojServerProperties.getDirectories().getBaseDirectory().toFile(),
				mojServerProperties.getDirectories().getLibDirectory(), "hamcrest-all-1.3.jar"));
		classPath.add(FileUtils.getFile(mojServerProperties.getDirectories().getBaseDirectory().toFile(),
				mojServerProperties.getDirectories().getLibDirectory(), "asciiart-core-1.1.0.jar"));

		for (File file : classPath) {
			if (!file.exists()) {
				log.error("not found: {}", file.getAbsolutePath());
			} else {
				log.debug("on cp: {}", file.getAbsolutePath());
			}
		}
		StringBuilder sb = new StringBuilder();
		for (File file : classPath) {
			sb.append(file.getAbsolutePath());
			sb.append(File.pathSeparator);
		}
		return sb.toString();
	}

}
