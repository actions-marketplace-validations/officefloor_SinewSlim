package net.officefloor.sinewslim.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.officefloor.sinewslim.cli.report.ClassReport;
import net.officefloor.sinewslim.cli.report.MethodReport;
import net.officefloor.sinewslim.cli.report.ScanReport;
import net.officefloor.sinewslim.config.Config;
import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.Binding;
import net.officefloor.sinewslim.core.SlimResult;
import net.officefloor.sinewslim.scan.Glob;
import net.officefloor.sinewslim.scan.Scanner;
import net.officefloor.sinewslim.scan.Scanner.FileResult;

/** The health scan: every selected file of a repository read in parallel, and every method in it assessed. */
public final class HealthScan {

	private HealthScan() {
	}

	public static ScanReport scan(Path root, Config config, int threads, String version) throws IOException {
		long started = System.nanoTime();
		List<Glob> include = config.include().stream().map(Glob::new).toList();
		List<Glob> exclude = config.exclude().stream().map(Glob::new).toList();
		List<FileResult<List<MethodReport>>> results = Scanner.scan(root, include, exclude, threads,
				Scanner.javaMethods(List.of(), (path, m) -> {
					Assessment a = config.assessor().assess(m.facts());
					return new MethodReport(path, m.type(), m.signature(), m.nameLine(), m.endLine(), m.facts().length(),
							a);
				}));

		List<ScanReport.Unparsed> unparsed = new ArrayList<>();
		List<MethodReport> methods = new ArrayList<>();
		for (FileResult<List<MethodReport>> r : results) {
			if (r.read()) {
				methods.addAll(r.result());
			} else {
				unparsed.add(new ScanReport.Unparsed(r.path(), r.problem()));
			}
		}
		double seconds = (System.nanoTime() - started) / 1e9;
		return new ScanReport(version, root.toString(), config, seconds, results.size(), unparsed, methods,
				classes(methods));
	}

	/** Per class with a method over the cap: its pieces, how many are locked, its methods that cannot be slimmed. */
	static List<ClassReport> classes(List<MethodReport> methods) {
		Map<String, int[]> by = new LinkedHashMap<>();
		Map<String, String[]> names = new LinkedHashMap<>();
		for (MethodReport m : methods) {
			if (m.status() == Status.UNDER_CAP) {
				continue;
			}
			String key = m.path() + "\0" + m.type();
			names.putIfAbsent(key, new String[] { m.path(), m.type() });
			int[] c = by.computeIfAbsent(key, k -> new int[6]);
			c[0]++;
			SlimResult p = m.proposal();
			if (p.feasible()) {
				c[1] += p.pieces();
				c[2] += p.count(Binding.LOCKED);
				c[3]++;
				c[4] += p.remainderLocked() ? 1 : 0;
			}
			if (m.status() == Status.NOT_SLIMMABLE) {
				c[5]++;
			}
		}
		List<ClassReport> out = new ArrayList<>();
		by.forEach((k, c) -> out.add(new ClassReport(names.get(k)[0], names.get(k)[1], c[0], c[1], c[2], c[3], c[4], c[5])));
		out.sort(Comparator.comparingDouble(ClassReport::lockedShare).reversed()
				.thenComparing(Comparator.comparingInt(ClassReport::overCap).reversed()).thenComparing(ClassReport::path)
				.thenComparing(ClassReport::type));
		return out;
	}
}
