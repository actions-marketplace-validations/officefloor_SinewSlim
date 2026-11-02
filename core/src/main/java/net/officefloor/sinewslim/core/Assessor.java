package net.officefloor.sinewslim.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.officefloor.sinewslim.core.Assessment.Obstacle;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Stmt;
import net.officefloor.sinewslim.facts.StmtKind;

/**
 * Reads a method under the default search and the variants beside it, and gives its status
 * (DESIGN.md, The result).
 *
 * @param params                  the limits and weights
 * @param records                 when nothing is feasible with one return, try records
 * @param structural              also solve with whole if, loop, try and switch statements only
 * @param preferStructuralWithin  show the structural proposal when it costs at most this much
 *                                more than the best; -1 never to prefer it
 */
public record Assessor(Params params, boolean records, boolean structural, int preferStructuralWithin) {

	/** SinewSlim's defaults (DESIGN.md, Configuration). */
	public static final Assessor DEFAULTS = new Assessor(Params.DEFAULTS, true, true, 1);

	public Assessment assess(MethodFacts facts) {
		return assess(facts, Set.of());
	}

	/** The assessment with the {@code excluded} runs left out of every search: the pieces the developer skipped. */
	public Assessment assess(MethodFacts facts, Set<Run> excluded) {
		SlimResult best = Slimmer.slim(facts, params, excluded);
		if (!best.applicable()) {
			return new Assessment(Status.UNDER_CAP, null, null, best, best, null);
		}
		SlimResult struct = structural && !params.structuralOnly() ? Slimmer.slim(facts, params.structural(), excluded) : null;
		if (best.feasible()) {
			return slimmable(best, struct);
		}
		Obstacle obstacle = obstacle(facts, excluded);
		if (records && obstacle == Obstacle.OUTPUTS) {
			SlimResult rec = Slimmer.slim(facts, params.records(), excluded);
			return new Assessment(Status.SLIMMABLE_WITH_RECORD, obstacle, null, rec, best, struct);
		}
		String detail = null;
		if (obstacle == Obstacle.LONG_STATEMENT) {
			detail = longStatement(facts);
			if (detail == null) {
				obstacle = Obstacle.LENGTH; // no one statement blocks: the method is simply too long
			}
		}
		return new Assessment(Status.NOT_SLIMMABLE, obstacle, detail, best, best, struct);
	}

	/** A feasible search's assessment: its proposal, the structural one when it costs little more. */
	private Assessment slimmable(SlimResult best, SlimResult struct) {
		SlimResult proposal = best;
		if (struct != null && struct.feasible() && preferStructuralWithin >= 0
				&& struct.cost() <= best.cost() + preferStructuralWithin) {
			proposal = struct;
		}
		Status status = locked(proposal) ? Status.SLIMMABLE_BUT_LOCKED : Status.SLIMMABLE;
		return new Assessment(status, null, null, proposal, best, struct);
	}

	/**
	 * Runs of one sequence of sibling statements, each assessed as a method of its own, for a
	 * caller that asks of many runs of the same sequence ({@link RunSlimmer}).
	 *
	 * @param facts    the method's facts
	 * @param siblings the sequence: a statement's kids, in order
	 */
	public Runs runs(MethodFacts facts, List<Stmt> siblings) {
		return new Runs(facts, siblings);
	}

	/** Runs of one sequence, assessed as {@link #assess(MethodFacts)} assesses a method. */
	public final class Runs {

		private final RunSlimmer best, struct;

		private Runs(MethodFacts facts, List<Stmt> siblings) {
			this.best = new RunSlimmer(facts, siblings, params, Set.of());
			this.struct = structural && !params.structuralOnly() ? new RunSlimmer(facts, siblings, params.structural(), Set.of())
					: null;
		}

		/** {@code siblings[from..to-1]} slimmed as the method {@code runFacts}, under the assessor's parameters. */
		public SlimResult slim(int from, int to, MethodFacts runFacts) {
			return best.slim(from, to, runFacts);
		}

		/** {@code siblings[from..to-1]} assessed as the method {@code runFacts}: the same as {@code assess(runFacts)}. */
		public Assessment assess(int from, int to, MethodFacts runFacts) {
			SlimResult b = best.slim(from, to, runFacts);
			if (!b.applicable()) {
				return new Assessment(Status.UNDER_CAP, null, null, b, b, null);
			}
			if (!b.feasible()) {
				return Assessor.this.assess(runFacts); // why not, and what would: the variants, searched afresh
			}
			return slimmable(b, struct == null ? null : struct.slim(from, to, runFacts));
		}
	}

	/** Every piece and the remainder bound to the class: splitting moves nothing out of it. */
	private static boolean locked(SlimResult r) {
		return r.pieces() > 0 && r.remainderLocked() && r.count(Binding.LOCKED) == r.pieces();
	}

	/** Which limit, lifted, makes a segmentation feasible. */
	private Obstacle obstacle(MethodFacts facts, Set<Run> excluded) {
		boolean outputsLimited = params.maxOutputs() >= 0, inputsLimited = params.maxInputs() >= 0;
		if (outputsLimited && Slimmer.slim(facts, params.records(), excluded).feasible()) {
			return Obstacle.OUTPUTS;
		}
		if (inputsLimited && Slimmer.slim(facts, params.unlimitedInputs(), excluded).feasible()) {
			return Obstacle.INPUTS;
		}
		Params unlimited = params.toBuilder().maxInputs(-1).maxOutputs(-1).build();
		if (outputsLimited && inputsLimited && Slimmer.slim(facts, unlimited, excluded).feasible()) {
			return Obstacle.INPUTS_AND_OUTPUTS;
		}
		if (Slimmer.slim(facts, unlimited.toBuilder().ignoreJumps(true).build(), excluded).feasible()) {
			return Obstacle.JUMPS;
		}
		return Obstacle.LONG_STATEMENT;
	}

	/**
	 * The statement that blocks: the longest of the innermost statements longer than the target,
	 * those with no statement over the target inside them.
	 */
	private String longStatement(MethodFacts facts) {
		MethodIndex m = new MethodIndex(facts, params.freeParams());
		Stmt[] found = new Stmt[1];
		int[] lines = new int[1];
		innermost(facts.body(), m, found, lines);
		if (found[0] == null) {
			return null;
		}
		Stmt s = found[0];
		return "the " + name(s.kind()) + " at lines " + s.start() + "-" + s.end() + " (" + lines[0] + " lines)";
	}

	/** Whether a statement over the target lies in {@code node}; records the longest innermost one. */
	private boolean innermost(Stmt node, MethodIndex m, Stmt[] found, int[] lines) {
		boolean inside = false;
		for (Stmt k : node.kids()) {
			inside |= innermost(k, m, found, lines);
		}
		if (inside || node.seq()) {
			return inside;
		}
		int n = m.lines(node.start(), node.end());
		if (n <= params.target()) {
			return false;
		}
		if (n > lines[0]) {
			found[0] = node;
			lines[0] = n;
		}
		return true;
	}

	private static String name(StmtKind k) {
		return switch (k) {
		case OTHER -> "statement";
		case LOOP -> "loop";
		case SYNCHRONIZED -> "synchronized block";
		case LABELED -> "labeled statement";
		case ENTRY -> "switch entry";
		default -> k.name().toLowerCase(Locale.ROOT) + " statement";
		};
	}
}
