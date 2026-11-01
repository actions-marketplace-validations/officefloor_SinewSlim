package net.officefloor.sinewslim.core;

import net.officefloor.sinewslim.facts.Seam;

/**
 * Every number the search decides by. {@link #DEFAULTS} are SinewSlim's (DESIGN.md,
 * Configuration); {@link #STUDY} are the study's ({@code SPLIT.md}, Parameters), so a run can be
 * set beside the paper's.
 *
 * @param trigger          a method with this many code lines or more is long
 * @param target           every piece, and the remainder, must end at this many or fewer
 * @param minPiece         the smallest run worth extracting, in code lines
 * @param minSaving        lines a cut must remove: the run's length less its call line
 * @param maxInputs        parameters a piece may need; -1 for no limit
 * @param maxOutputs       values a piece may return (Java: one); -1 for no limit (a record)
 * @param softInputs       parameters beyond this cost {@code inputPremium} more each; -1 for no
 *                         soft limit
 * @param inputPremium     the extra cost per parameter beyond {@code softInputs}
 * @param outputWeight     a single return value: a plain method call
 * @param multiOutputWeight per value returned when a piece returns two or more: a record type
 *                         declared, packed and unpacked
 * @param pieceCost        a name and a call site, per piece
 * @param trailingReturn   a run may end in a return; any other return leaves it
 * @param structuralOnly   pieces are whole if, loop, try and switch statements only
 * @param lockOnAnyField   a field read locks a piece too (otherwise a write, or an own call)
 * @param freeParams       a parameter of the method handed to a piece unchanged costs nothing;
 *                         off by default, and kept for the study's {@code split.py} variant that
 *                         sets it (the parity data replays it)
 * @param ignoreJumps      a jump leaving a run does not stop it moving: a diagnostic only, to
 *                         tell a method blocked by its jumps from one blocked by a long statement
 * @param countUnpacking   a piece returning a record leaves one line per value behind as well as
 *                         its call: {@code int a = totals.a();}. Off in the study (split.py counts
 *                         the call alone)
 */
public record Params(int trigger, int target, int minPiece, int minSaving, int maxInputs, int maxOutputs, int softInputs,
		int inputPremium, int outputWeight, int multiOutputWeight, int pieceCost, boolean trailingReturn,
		boolean structuralOnly, boolean lockOnAnyField, boolean freeParams, boolean ignoreJumps, boolean countUnpacking) {

	/** SinewSlim's defaults: the study's cap, smaller pieces, a hard limit of five parameters. */
	public static final Params DEFAULTS = new Params(26, 25, 3, 2, 5, 1, -1, 2, 1, 3, 1, true, false, false, false, false, true);

	/** The study's defaults ({@code SPLIT.md}, Parameters; {@code split.py}, Params). */
	public static final Params STUDY = DEFAULTS.toBuilder().minPiece(5).minSaving(3).maxInputs(3).countUnpacking(false).build();

	/** The parameters' share of a seam: one each, and the premium for each beyond the soft limit. */
	public int inputCost(Seam s) {
		int k = s.inputs().size();
		return softInputs < 0 ? k : k + inputPremium * Math.max(0, k - softInputs);
	}

	/** The worst-seam measure: what crosses, the soft-limit premium included. */
	public int width(Seam s) {
		return inputCost(s) + s.outputs().size();
	}

	/** The return values' share of a seam: one plain return, or a record's values. */
	public int outputCost(Seam s) {
		int k = s.outputs().size();
		return k <= 1 ? outputWeight * k : multiOutputWeight * k;
	}

	/** A seam extracted as a method: its parameters, its returns, a name and a call site. */
	public int cost(Seam s) {
		return inputCost(s) + outputCost(s) + pieceCost;
	}

	/** Whether a seam is within the limits on parameters and return values. */
	public boolean feasible(Seam s) {
		return (maxOutputs < 0 || s.outputs().size() <= maxOutputs) && (maxInputs < 0 || s.inputs().size() <= maxInputs);
	}

	/** What a piece extracted at a seam depends on in its class. */
	public Binding binding(Seam s) {
		if (!s.fieldsWritten().isEmpty() || s.ownCalls() > 0 || (lockOnAnyField && !s.fieldsRead().isEmpty())) {
			return Binding.LOCKED;
		}
		return s.fieldsRead().isEmpty() ? Binding.FREE : Binding.BOUND;
	}

	/** The same search restricted to whole if, loop, try and switch statements. */
	public Params structural() {
		return toBuilder().structuralOnly(true).build();
	}

	/** Several returns allowed, each costed as a record's value. */
	public Params records() {
		return toBuilder().maxOutputs(-1).build();
	}

	/** No limit on the parameters a piece may need. */
	public Params unlimitedInputs() {
		return toBuilder().maxInputs(-1).build();
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static final class Builder {
		private int trigger, target, minPiece, minSaving, maxInputs, maxOutputs, softInputs, inputPremium, outputWeight,
				multiOutputWeight, pieceCost;
		private boolean trailingReturn, structuralOnly, lockOnAnyField, freeParams, ignoreJumps, countUnpacking;

		private Builder(Params p) {
			trigger = p.trigger;
			target = p.target;
			minPiece = p.minPiece;
			minSaving = p.minSaving;
			maxInputs = p.maxInputs;
			maxOutputs = p.maxOutputs;
			softInputs = p.softInputs;
			inputPremium = p.inputPremium;
			outputWeight = p.outputWeight;
			multiOutputWeight = p.multiOutputWeight;
			pieceCost = p.pieceCost;
			trailingReturn = p.trailingReturn;
			structuralOnly = p.structuralOnly;
			lockOnAnyField = p.lockOnAnyField;
			freeParams = p.freeParams;
			ignoreJumps = p.ignoreJumps;
			countUnpacking = p.countUnpacking;
		}

		public Builder trigger(int v) {
			trigger = v;
			return this;
		}

		public Builder target(int v) {
			target = v;
			return this;
		}

		public Builder minPiece(int v) {
			minPiece = v;
			return this;
		}

		public Builder minSaving(int v) {
			minSaving = v;
			return this;
		}

		public Builder maxInputs(int v) {
			maxInputs = v;
			return this;
		}

		public Builder maxOutputs(int v) {
			maxOutputs = v;
			return this;
		}

		public Builder softInputs(int v) {
			softInputs = v;
			return this;
		}

		public Builder inputPremium(int v) {
			inputPremium = v;
			return this;
		}

		public Builder outputWeight(int v) {
			outputWeight = v;
			return this;
		}

		public Builder multiOutputWeight(int v) {
			multiOutputWeight = v;
			return this;
		}

		public Builder pieceCost(int v) {
			pieceCost = v;
			return this;
		}

		public Builder trailingReturn(boolean v) {
			trailingReturn = v;
			return this;
		}

		public Builder structuralOnly(boolean v) {
			structuralOnly = v;
			return this;
		}

		public Builder lockOnAnyField(boolean v) {
			lockOnAnyField = v;
			return this;
		}

		public Builder freeParams(boolean v) {
			freeParams = v;
			return this;
		}

		public Builder ignoreJumps(boolean v) {
			ignoreJumps = v;
			return this;
		}

		public Builder countUnpacking(boolean v) {
			countUnpacking = v;
			return this;
		}

		public Params build() {
			return new Params(trigger, target, minPiece, minSaving, maxInputs, maxOutputs, softInputs, inputPremium,
					outputWeight, multiOutputWeight, pieceCost, trailingReturn, structuralOnly, lockOnAnyField,
					freeParams, ignoreJumps, countUnpacking);
		}
	}
}
