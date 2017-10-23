package nl.moj.server;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import nl.moj.server.competition.Competition;
import nl.moj.server.persistence.TeamMapper;
import nl.moj.server.test.TestResult;

@Controller
public class FeedbackController {

	private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

	@Autowired
	private TeamMapper teamMapper;

	@Autowired
	private Competition competition;
	@Autowired
	private SimpMessagingTemplate template;

	@GetMapping("/feedback")
	public ModelAndView feedback() {
		ModelAndView model = new ModelAndView("testfeedback");
		model.addObject("teams", teamMapper.getAllTeams());
		List<String> testNames = new ArrayList<>();
		if (competition.getCurrentAssignment() != null) {
			testNames = competition.getCurrentAssignment().getTestNames();	
		} 
		model.addObject("tests", testNames);

		return model;
	}

	public void sendFeedbackMessage(TestResult testResult) {
		log.info("sending testResult feedback");
		template.convertAndSendToUser(testResult.getUser(), "/queue/feedback", new TestFeedbackMessage(
				testResult.getUser(), testResult.getTestname(), testResult.getTestResult(), testResult.isSuccessful()));
		template.convertAndSend("/queue/testfeedback", new TestFeedbackMessage(testResult.getUser(),
				testResult.getTestname(), null, testResult.isSuccessful()));
	}

	public static class TestFeedbackMessage {
		private String team;
		private String test;
		private String text;
		private Boolean success;

		public TestFeedbackMessage() {
		}

		public TestFeedbackMessage(String team, String test, String text, Boolean success) {
			super();
			this.team = team;
			this.test = test;
			this.text = text;
			this.success = success;
		}

		public String getTeam() {
			return team;
		}

		public void setTeam(String team) {
			this.team = team;
		}

		public String getTest() {
			return test;
		}

		public void setTest(String test) {
			this.test = test;
		}

		public String getText() {
			return text;
		}

		public void setText(String text) {
			this.text = text;
		}

		public Boolean isSuccess() {
			return success;
		}

		public void setSuccess(Boolean success) {
			this.success = success;
		}

	}
}
