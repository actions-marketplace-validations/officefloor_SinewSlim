package net.officefloor.sinewslim.cli.report;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.officefloor.sinewslim.config.Config;
import net.officefloor.sinewslim.core.Assessment.Status;

/**
 * Everything a scan found, in the order the reports show it.
 *
 * @param version  the tool's version
 * @param root     the repository scanned
 * @param config   what it was read under
 * @param seconds  how long the scan took
 * @param files    the source files read
 * @param unparsed the files that could not be read, and why
 * @param methods  every method, by file then line
 * @param classes  every class with a method over the cap, by locked share then size
 */
public record ScanReport(String version, String root, Config config, double seconds, int files, List<Unparsed> unparsed,
		List<MethodReport> methods, List<ClassReport> classes) {

	/** A file left out of the scan. */
	public record Unparsed(String path, String problem) {
	}

	/** The JSON schema the report is written in; bumped with any change to its shape. */
	public static final int SCHEMA = 2;

	public ScanReport {
		unparsed = List.copyOf(unparsed);
		methods = List.copyOf(methods);
		classes = List.copyOf(classes);
	}

	public List<MethodReport> with(Status status) {
		return methods.stream().filter(m -> m.status() == status).toList();
	}

	public long count(Status status) {
		return methods.stream().filter(m -> m.status() == status).count();
	}

	public long overCap() {
		return methods.size() - count(Status.UNDER_CAP);
	}

	/** {@code Σ max(0, length − target)}: the lines over the cap across the codebase. */
	public long excess() {
		int target = config.params().target();
		return methods.stream().mapToLong(m -> Math.max(0, m.length() - target)).sum();
	}

	/** The methods under the cap within {@code near} lines of the trigger, closest first. */
	public List<MethodReport> nearCap(int near) {
		return with(Status.UNDER_CAP).stream().filter(m -> m.headroom() <= near)
				.sorted(Comparator.comparingInt(MethodReport::headroom).thenComparing(MethodReport::path)
						.thenComparingInt(MethodReport::nameLine))
				.toList();
	}

	/** The slimmable methods, cheapest slim cost first, then longest. */
	public List<MethodReport> slimmable() {
		return with(Status.SLIMMABLE).stream().sorted(Comparator.comparingInt((MethodReport m) -> m.proposal().width())
				.thenComparing(Comparator.comparingInt(MethodReport::length).reversed())).toList();
	}

	/** Longest first: the most urgent of a list a developer works down. */
	public List<MethodReport> longestFirst(Status status) {
		return with(status).stream().sorted(Comparator.comparingInt(MethodReport::length).reversed()
				.thenComparing(MethodReport::path).thenComparingInt(MethodReport::nameLine)).toList();
	}

	/** How many slimmable methods have each slim cost. */
	public Map<Integer, Integer> slimCostDistribution() {
		Map<Integer, Integer> out = new TreeMap<>();
		for (MethodReport m : with(Status.SLIMMABLE)) {
			out.merge(m.proposal().width(), 1, Integer::sum);
		}
		return out;
	}
}
