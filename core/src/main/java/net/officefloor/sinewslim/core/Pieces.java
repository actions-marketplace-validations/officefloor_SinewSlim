package net.officefloor.sinewslim.core;

import java.util.ArrayList;
import java.util.List;

import net.officefloor.sinewslim.facts.Seam;

/**
 * The words for a proposal and its pieces, shared by every surface (the reports, the review, the
 * IDE) so they all say the same thing.
 */
public final class Pieces {

	private Pieces() {
	}

	/**
	 * One piece of a proposal, ready to show.
	 *
	 * @param seam    what crosses its boundary
	 * @param lines   its code lines once the pieces inside it are out
	 * @param saves   the lines its extraction removes: its lines less the one call line
	 * @param inputs  its parameters that are values computed before it
	 * @param passed  its parameters that are the method's own, handed on unchanged
	 * @param binding where it can live: {@code static}, {@code static if passed: cfg}, {@code instance: writes cache}
	 */
	public record Piece(Seam seam, int lines, int saves, List<String> inputs, List<String> passed, String binding) {

		public int lo() {
			return seam.lo();
		}

		public int hi() {
			return seam.hi();
		}

		public List<String> outputs() {
			return seam.outputs();
		}

		/** The declarations that move into the new method. */
		public List<String> moved() {
			return seam.moved();
		}

		/** One line: {@code 12 lines · in: request, config · out: session · static · saves 11}. */
		public String summary(String separator) {
			List<String> parts = new ArrayList<>();
			parts.add(lines + " lines");
			parts.add("in: " + list(inputs) + (passed.isEmpty() ? "" : " (passed: " + list(passed) + ")"));
			parts.add("out: " + Pieces.outputs(outputs()));
			if (!moved().isEmpty()) {
				parts.add("declares: " + list(moved()));
			}
			parts.add(binding);
			parts.add("saves " + saves);
			return String.join(separator, parts);
		}
	}

	public static List<Piece> of(SlimResult r, boolean parameteriseFieldReads) {
		List<Piece> out = new ArrayList<>();
		for (int i = 0; i < r.cuts().size(); i++) {
			Seam s = r.cuts().get(i);
			int lines = r.pieceLines().get(i);
			List<String> computed = new ArrayList<>(s.inputs());
			computed.removeAll(s.passed());
			out.add(new Piece(s, lines, lines - 1, computed, s.passed(), binding(s, r.params(), parameteriseFieldReads)));
		}
		return out;
	}

	/** Where the piece can live: {@code static}, {@code static if passed: cfg}, or {@code instance: writes cache}. */
	public static String binding(Seam s, Params p, boolean parameteriseFieldReads) {
		Binding b = p.binding(s);
		if (b == Binding.FREE) {
			return "static";
		}
		if (b == Binding.BOUND) {
			boolean fits = p.maxInputs() < 0 || s.inputs().size() + s.fieldsRead().size() <= p.maxInputs();
			return parameteriseFieldReads && fits ? "static if passed: " + String.join(", ", s.fieldsRead())
					: "instance: reads " + String.join(", ", s.fieldsRead());
		}
		List<String> why = new ArrayList<>();
		if (!s.fieldsWritten().isEmpty()) {
			why.add("writes " + String.join(", ", s.fieldsWritten()));
		}
		if (s.ownCalls() > 0) {
			why.add("calls " + s.ownCalls() + " own method" + (s.ownCalls() == 1 ? "" : "s"));
		}
		if (why.isEmpty()) {
			why.add("reads " + String.join(", ", s.fieldsRead()));
		}
		return "instance: " + String.join("; ", why);
	}

	/** What crosses out: {@code -}, the one value, or a record of several. */
	public static String outputs(List<String> outputs) {
		if (outputs.isEmpty()) {
			return "-";
		}
		return outputs.size() == 1 ? outputs.get(0) : "record(" + String.join(", ", outputs) + ")";
	}

	public static String list(List<String> xs) {
		return xs.isEmpty() ? "-" : String.join(", ", xs);
	}

	/** The verdict on a method, without its length: {@code slimmable at cost 2 (2 pieces, remainder 21 lines)}. */
	public static String verdict(Assessment a, int length) {
		SlimResult r = a.proposal();
		String pieces = r.pieces() + " piece" + (r.pieces() == 1 ? "" : "s");
		int headroom = r.params().trigger() - length;
		return switch (a.status()) {
		case UNDER_CAP -> headroom + (headroom == 1 ? " line" : " lines") + " from the cap";
		case SLIMMABLE -> "slimmable at cost " + r.width() + " (" + pieces + ", remainder " + r.remainder() + " lines)";
		case SLIMMABLE_WITH_RECORD -> "slimmable only by returning a record (cost " + r.width() + ", " + pieces + "): "
				+ a.reason();
		case SLIMMABLE_BUT_LOCKED -> "slimmable at cost " + r.width() + " (" + pieces
				+ "), but every piece stays bound to the class: the class, not the method, is the problem";
		case NOT_SLIMMABLE -> "cannot be slimmed: " + a.reason() + ". Investigate for redesign";
		};
	}

	/** What the remainder of a proposal is, against what the method was. */
	public static String remainder(SlimResult r, int length) {
		return r.remainder() + " lines (was " + length + ")" + (r.remainderLocked() ? ", bound to the class" : "");
	}
}
