package net.officefloor.sinewslim.cli.report;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import net.officefloor.sinewslim.core.Assessment.Status;

/**
 * Plain text for a terminal. Every finding starts {@code path:line:} so IDE terminals turn it into
 * a link to the method.
 */
final class TextReport implements ReportWriter {

	@Override
	public void write(ScanReport r, int near, Appendable out) throws IOException {
		int target = r.config().params().target(), trigger = r.config().params().trigger();
		out.append(String.format("sinew-slim %s: %d methods in %d files (%.1fs); cap %d lines, pieces and remainder to %d%n",
				r.version(), r.methods().size(), r.files(), r.seconds(), trigger, target));
		out.append(String.format("  %6d under the cap%n", r.count(Status.UNDER_CAP)));
		out.append(String.format("  %6d over the cap (%d lines over in all)%n", r.overCap(), r.excess()));
		out.append(String.format("  %6d   slimmable%n", r.count(Status.SLIMMABLE)));
		out.append(String.format("  %6d   slimmable only by returning a record%n", r.count(Status.SLIMMABLE_WITH_RECORD)));
		out.append(String.format("  %6d   slimmable, but every piece stays bound to the class%n",
				r.count(Status.SLIMMABLE_BUT_LOCKED)));
		out.append(String.format("  %6d   not slimmable: investigate for redesign%n", r.count(Status.NOT_SLIMMABLE)));
		if (!r.unparsed().isEmpty()) {
			out.append(String.format("  %6d files could not be read (listed at the end)%n", r.unparsed().size()));
		}
		Map<Integer, Integer> dist = r.slimCostDistribution();
		if (!dist.isEmpty()) {
			out.append("  slim cost of the slimmable:");
			for (Map.Entry<Integer, Integer> e : dist.entrySet()) {
				out.append("  ").append(String.valueOf(e.getKey())).append(": ").append(String.valueOf(e.getValue()));
			}
			out.append(System.lineSeparator());
		}

		boolean pfr = r.config().parameteriseFieldReads();
		section(out, "Not slimmable: investigate for redesign", r.longestFirst(Status.NOT_SLIMMABLE), false, pfr);
		section(out, "Slimmable, but every piece stays bound to the class", r.longestFirst(Status.SLIMMABLE_BUT_LOCKED), true, pfr);
		section(out, "Slimmable only by returning a record", r.longestFirst(Status.SLIMMABLE_WITH_RECORD), true, pfr);
		section(out, "Slimmable, cheapest first", r.slimmable(), true, pfr);

		List<MethodReport> close = r.nearCap(near);
		if (!close.isEmpty()) {
			heading(out, "Under the cap, within " + near + " lines of it");
			for (MethodReport m : close) {
				out.append(m.location()).append(": ").append(m.qualified()).append("  ").append(Describe.verdict(m))
						.append(System.lineSeparator());
			}
		}

		List<ClassReport> classes = r.classes().stream().filter(c -> c.pieces() + c.remainders() > 0 || c.notSlimmable() > 0)
				.toList();
		if (!classes.isEmpty()) {
			heading(out, "Classes, by locked share");
			out.append(String.format("%-7s %5s %7s %5s  %s%n", "locked", "long", "pieces", "not", "class"));
			for (ClassReport c : classes) {
				out.append(String.format("%-7s %5d %7d %5d  %s:%s%n", Describe.percent(c.lockedShare()), c.overCap(),
						c.pieces(), c.notSlimmable(), c.path(), c.type()));
			}
		}

		if (!r.unparsed().isEmpty()) {
			heading(out, "Files that could not be read");
			for (ScanReport.Unparsed u : r.unparsed()) {
				out.append(u.path()).append(": ").append(u.problem()).append(System.lineSeparator());
			}
		}
	}

	static void heading(Appendable out, String title) throws IOException {
		out.append(System.lineSeparator()).append("== ").append(title).append(" ==").append(System.lineSeparator());
	}

	static void section(Appendable out, String title, List<MethodReport> methods, boolean proposals, boolean pfr)
			throws IOException {
		if (methods.isEmpty()) {
			return;
		}
		heading(out, title + " (" + methods.size() + ")");
		for (MethodReport m : methods) {
			out.append(m.location()).append(": ").append(m.qualified()).append("  ").append(Describe.verdict(m))
					.append(System.lineSeparator());
			if (proposals && m.proposal().feasible()) {
				proposal(out, m, "  ", pfr);
			}
		}
	}

	static void proposal(Appendable out, MethodReport m, String indent, boolean pfr) throws IOException {
		for (String line : Describe.proposalLines(m, pfr)) {
			out.append(indent).append(line).append(System.lineSeparator());
		}
	}
}
