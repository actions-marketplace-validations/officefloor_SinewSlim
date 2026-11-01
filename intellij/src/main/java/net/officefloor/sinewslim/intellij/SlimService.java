package net.officefloor.sinewslim.intellij;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;

import net.officefloor.sinewslim.config.Config;
import net.officefloor.sinewslim.config.ConfigLoader;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Run;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;

/**
 * The project's SinewSlim: its configuration, read from {@code .sinew-slim.yml} at the project
 * root as every other surface reads it, and the analysis of each Java document, computed from
 * the document's text by the same parser and search as the command line, so the IDE and CI give
 * the same proposal for the same code.
 */
@Service(Service.Level.PROJECT)
public final class SlimService {

	static final Logger LOG = Logger.getInstance(SlimService.class);

	/** One method of a document and what SinewSlim says about it. */
	public record MethodAnalysis(MethodUnit unit, Assessment assessment) {
	}

	/** A document's methods, as of one version of its text. */
	public record FileAnalysis(long stamp, List<MethodAnalysis> methods) {

		/** The method whose name is on this line (1-based), or null. */
		public MethodAnalysis atNameLine(int line) {
			for (MethodAnalysis m : methods) {
				if (m.unit().nameLine() == line) {
					return m;
				}
			}
			return null;
		}
	}

	private final Project project;
	private final Map<Document, FileAnalysis> analyses = new WeakHashMap<>();
	private Config config;
	private long configStamp = Long.MIN_VALUE;
	private String reportedProblem;
	private String reportedWarnings;

	public SlimService(Project project) {
		this.project = project;
	}

	public static SlimService get(Project project) {
		return project.getService(SlimService.class);
	}

	/** The configuration: the project's {@code .sinew-slim.yml} over the defaults, reread when it changes. */
	public synchronized Config config() {
		Path root = project.getBasePath() == null ? null : Path.of(project.getBasePath());
		Path file = root == null ? null : root.resolve(ConfigLoader.FILE);
		long stamp = -1;
		try {
			stamp = file != null && Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
		} catch (java.io.IOException e) {
			// read as if absent
		}
		if (config == null || stamp != configStamp) {
			configStamp = stamp;
			try {
				config = root == null ? ConfigLoader.defaults() : ConfigLoader.load(root, null, Map.of());
				reportedProblem = null;
				String warnings = String.join("; ", config.warnings());
				if (!warnings.isEmpty() && !warnings.equals(reportedWarnings)) {
					reportedWarnings = warnings;
					NotificationGroupManager.getInstance().getNotificationGroup("SinewSlim")
							.createNotification(".sinew-slim.yml: " + warnings, NotificationType.WARNING).notify(project);
				}
			} catch (ConfigLoader.ConfigException e) {
				config = ConfigLoader.defaults();
				if (!e.getMessage().equals(reportedProblem)) {
					reportedProblem = e.getMessage();
					NotificationGroupManager.getInstance().getNotificationGroup("SinewSlim")
							.createNotification(".sinew-slim.yml: " + e.getMessage() + ". Using the defaults.",
									NotificationType.WARNING)
							.notify(project);
				}
			}
			analyses.clear();
		}
		return config;
	}

	/** The analysis of a document's current text, computed once per version of it. */
	public FileAnalysis analyse(Document document, String path) {
		Config c = config();
		long stamp = document.getModificationStamp();
		synchronized (this) {
			FileAnalysis cached = analyses.get(document);
			if (cached != null && cached.stamp() == stamp) {
				return cached;
			}
		}
		FileAnalysis a = new FileAnalysis(stamp, compute(document.getText(), path, c));
		synchronized (this) {
			analyses.put(document, a);
		}
		return a;
	}

	static List<MethodAnalysis> compute(String text, String path, Config c) {
		try {
			ParsedFile f = JavaFacts.parse(path, text);
			if (!f.parsed()) {
				return List.of(); // mid-edit: nothing to say until it parses again
			}
			return f.methods().stream()
					.map(m -> new MethodAnalysis(m, c.assessor().assess(m.facts())))
					.toList();
		} catch (StackOverflowError | RuntimeException e) {
			LOG.warn("SinewSlim could not read " + path, e);
			return List.of();
		}
	}

	/** One method again, the {@code excluded} runs left out: after the developer skips a piece. */
	public Assessment reassess(MethodAnalysis m, Set<Run> excluded) {
		return config().assessor().assess(m.unit().facts(), excluded);
	}
}
