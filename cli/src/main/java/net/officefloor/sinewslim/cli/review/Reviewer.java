package net.officefloor.sinewslim.cli.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.officefloor.sinewslim.cli.report.MethodReport;
import net.officefloor.sinewslim.cli.report.ScanReport;
import net.officefloor.sinewslim.config.Config;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;
import net.officefloor.sinewslim.scan.Glob;

/**
 * Reviews the methods a change touches (DESIGN.md, Review). A method is touched when a changed
 * hunk overlaps its span on the head side; nothing is said about a method the change did not
 * touch. Each touched method is matched to its base by class and signature, falling back to its
 * name when the signature changed, and its length before and after decides what is said.
 */
public final class Reviewer {

	/** Where {@code sinew-slim baseline} writes, read when there is no base commit to compare with. */
	public static final String BASELINE = ".sinew-slim-baseline.json";

	private Reviewer() {
	}

	public static ReviewReport review(Path root, Config config, String mode, String baseRef, String version)
			throws IOException {
		Change change = Change.of(root, Change.Mode.of(mode), baseRef);
		Map<String, Integer> baseline = change.base == null ? baseline(root) : Map.of();
		List<Finding> findings = new ArrayList<>();
		List<ScanReport.Unparsed> unparsed = new ArrayList<>();
		int files = 0, touched = 0;
		List<Glob> include = config.include().stream().map(Glob::new).toList();
		List<Glob> exclude = config.exclude().stream().map(Glob::new).toList();
		for (Diff.FileChange fc : change.files) {
			if (fc.newPath() == null || !Glob.selected(fc.newPath(), include, exclude)) {
				continue; // deleted, or not a file the configuration reviews
			}
			files++;
			ParsedFile head = JavaFacts.parse(fc.newPath(), change.head(fc.newPath()));
			if (!head.parsed()) {
				unparsed.add(new ScanReport.Unparsed(fc.newPath(), head.problem()));
				continue;
			}
			Map<String, Integer> before = new HashMap<>();
			if (fc.oldPath() != null && change.base != null) {
				ParsedFile base = JavaFacts.parse(fc.oldPath(), change.base(fc.oldPath()));
				if (base.parsed()) {
					for (MethodUnit m : base.methods()) {
						for (String k : keys(m)) {
							before.merge(k, m.facts().length(), (a, b) -> -1); // -1: the key is ambiguous
						}
					}
				}
			} else if (change.base == null) {
				for (Map.Entry<String, Integer> e : baseline.entrySet()) {
					if (e.getKey().startsWith(fc.newPath() + "|")) {
						before.put("sig:" + e.getKey().substring(fc.newPath().length() + 1), e.getValue());
					}
				}
			}
			Map<String, Integer> headKeys = new HashMap<>();
			for (MethodUnit m : head.methods()) {
				for (String k : keys(m)) {
					headKeys.merge(k, 1, Integer::sum);
				}
			}
			for (MethodUnit m : head.methods()) {
				if (!fc.touches(m.declLine(), m.endLine())) {
					continue;
				}
				touched++;
				Finding f = finding(fc.newPath(), m, match(m, before, headKeys), config);
				if (f != null) {
					findings.add(f);
				}
			}
		}
		return new ReviewReport(version, change.mode.name().toLowerCase(Locale.ROOT), change.description,
				config, files, touched, findings, unparsed);
	}

	/** What is said about one touched method, or null when it is under the cap and was. */
	static Finding finding(String path, MethodUnit m, int before, Config config) {
		int trigger = config.params().trigger(), after = m.facts().length();
		boolean overNow = after >= trigger, overBefore = before >= trigger;
		Finding.Kind kind;
		if (!overNow) {
			if (!overBefore) {
				return null;
			}
			kind = Finding.Kind.SLIMMED;
		} else if (before < 0) {
			kind = Finding.Kind.NEW_OVER;
		} else if (!overBefore) {
			kind = Finding.Kind.CROSSED;
		} else if (after > before) {
			kind = Finding.Kind.GREW;
		} else {
			kind = Finding.Kind.STILL_OVER;
		}
		boolean gates = switch (kind) {
		case CROSSED, GREW, NEW_OVER -> true;
		case STILL_OVER -> !config.ratchet();
		case SLIMMED -> false;
		};
		Assessment a = config.assessor().assess(m.facts());
		MethodReport r = new MethodReport(path, m.type(), m.signature(), m.nameLine(), m.endLine(), after, a);
		return new Finding(kind, r, before, gates);
	}

	/**
	 * The keys a method is matched to its base by, most exact first: its class and signature; its
	 * class and name, when the signature changed; its name alone, when the class was renamed with
	 * its file.
	 */
	static List<String> keys(MethodUnit m) {
		return List.of("sig:" + m.type() + "|" + m.signature(), "name:" + m.type() + "." + m.name(), "bare:" + m.name());
	}

	/** The method's length before the change, or -1 for a new method: the first key unique on both sides. */
	static int match(MethodUnit m, Map<String, Integer> before, Map<String, Integer> headKeys) {
		for (String k : keys(m)) {
			Integer was = before.get(k);
			if (was != null && was >= 0 && (k.startsWith("sig:") || headKeys.get(k) == 1)) {
				return was;
			}
		}
		return -1;
	}

	/** {@code path|type|signature} to length, from the baseline file, when there is one. */
	static Map<String, Integer> baseline(Path root) throws IOException {
		Path f = root.resolve(BASELINE);
		Map<String, Integer> out = new HashMap<>();
		if (!Files.isRegularFile(f)) {
			return out;
		}
		JsonNode doc = new ObjectMapper().readTree(f.toFile());
		for (JsonNode m : doc.path("methods")) {
			out.put(m.get("path").asText() + "|" + m.get("type").asText() + "|" + m.get("signature").asText(),
					m.get("length").asInt());
		}
		return out;
	}
}
