package nl.moj.server;

import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import nl.moj.server.competition.Competition;
import nl.moj.server.files.AssignmentFile;
import nl.moj.server.files.FileType;

@Controller
public class IndexController {

	@Autowired
	private Competition competition;

	@GetMapping("/")
	public String index(Model model) {
		if (competition.getCurrentAssignment() == null) {
			return "index";
		}
		List<AssignmentFile> files = competition.getCurrentAssignment().getJavaFiles();
		List<AssignmentFile> testfiles = competition.getCurrentAssignment().getTestFiles();
		files.addAll(competition.getCurrentAssignment().getTaskFiles());
		files.addAll(testfiles);
		
		files.sort(new Comparator<AssignmentFile>() {

			@Override
			public int compare(AssignmentFile arg0, AssignmentFile arg1) {
				if (arg0.getFileType().equals(FileType.TASK)) {
					return -10;
				}
				return 10;
			}
		});
		model.addAttribute("testnames", competition.getCurrentAssignment().getTestNames());
		model.addAttribute("files", files);
		return "index";
	}

	@GetMapping(value = "index.js")
	public String common(Model model) {
		if (competition.getCurrentAssignment() == null) {
			return "index";
		}
		List<AssignmentFile> files = competition.getCurrentAssignment().getJavaFiles();
		files.addAll(competition.getCurrentAssignment().getTaskFiles());
		List<AssignmentFile> testfiles = competition.getCurrentAssignment().getTestFiles();
		files.addAll(testfiles);
		model.addAttribute("files", files);
		return "index.js";
	}
}
