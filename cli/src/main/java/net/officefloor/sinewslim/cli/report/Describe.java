package net.officefloor.sinewslim.cli.report;

import java.util.ArrayList;
import java.util.List;

import net.officefloor.sinewslim.core.Pieces;
import net.officefloor.sinewslim.core.SlimResult;

/**
 * The words the reports use for a method, over the core's {@link Pieces}, so every format and
 * every surface says the same thing.
 */
public final class Describe {

	private Describe() {
	}

	public static List<Pieces.Piece> pieces(SlimResult r, boolean parameteriseFieldReads) {
		return Pieces.of(r, parameteriseFieldReads);
	}

	public static String outputs(List<String> outputs) {
		return Pieces.outputs(outputs);
	}

	public static String list(List<String> xs) {
		return Pieces.list(xs);
	}

	/** The one-line verdict for a method over the cap. */
	public static String verdict(MethodReport m) {
		return m.length() + " lines; " + assessment(m);
	}

	/** The verdict without the length, for where the length is already said (the review's movement). */
	public static String assessment(MethodReport m) {
		return Pieces.verdict(m.assessment(), m.length());
	}

	/** A proposal as plain lines, one per piece and one for the remainder, as the terminal shows it. */
	public static List<String> proposalLines(MethodReport m, boolean parameteriseFieldReads) {
		List<String> out = new ArrayList<>();
		for (Pieces.Piece p : pieces(m.proposal(), parameteriseFieldReads)) {
			out.add(String.format("lines %d-%d  %3d lines  in: %s%s  out: %s%s  %s  saves %d", p.lo(), p.hi(), p.lines(),
					list(p.inputs()), p.passed().isEmpty() ? "" : "  passed: " + list(p.passed()), outputs(p.outputs()),
					p.moved().isEmpty() ? "" : "  declares: " + list(p.moved()), p.binding(), p.saves()));
		}
		out.add("remainder " + remainder(m));
		return out;
	}

	/** What the remainder of a proposal is, against what the method was. */
	public static String remainder(MethodReport m) {
		return Pieces.remainder(m.proposal(), m.length());
	}

	public static String percent(double share) {
		return Math.round(share * 100) + "%";
	}
}
