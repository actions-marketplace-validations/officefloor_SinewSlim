package net.officefloor.sinewslim.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Stmt;
import net.officefloor.sinewslim.facts.StmtKind;
import net.officefloor.sinewslim.facts.Var;

/**
 * Slims runs of one sequence of sibling statements, each as a method of its own, as
 * {@link Slimmer#slim(MethodFacts, Params, Set)} slims that method, for a caller that asks of
 * many runs of the same sequence.
 *
 * <p>
 * Asked of every run of a sequence of {@code n} statements, slimming each from scratch searches
 * about {@code n²/2} sequences. The search walks a sequence left to right, and the states after
 * the statements {@code from..i-1} do not depend on what follows them. So one walk from
 * {@code from} serves every run starting there: about {@code n} walks in all.
 *
 * <p>
 * A run as a method of its own is {@link #runFacts}: the method's variables, a field cut to its
 * uses in the run, the loops and own calls in the run. The walk reads the whole sequence as such
 * a method. Inside any run, that indexes as the run's own facts do: the same code lines (each
 * statement's, and no line between statements, which the method's own index may count) and the
 * same seams, provided no loop encloses the sequence. A run's length and the lines its search
 * covers are checked against the facts it is asked of, and it is slimmed from scratch if they
 * differ; what stays behind (the lock) is read from its own facts.
 */
public final class RunSlimmer {

	private final List<Stmt> kids;
	private final Params p;
	private final Set<Run> excluded;
	/** The method's own index: a run's length, as {@link #runFacts} gives it. */
	private final MethodIndex lengths;

	/** The whole sequence as a method of its own: what the walk reads. */
	private final MethodIndex m;
	private final Search search;
	private List<List<Search.Option>> opts;

	/**
	 * By the run's first statement, then by its end less that: the best option, or null when none
	 * fits. Only as far as the walk went: no run that ends after it fits.
	 */
	private final Search.Option[][] best;

	/** The lines the search covers of the statements before each index: a run's are the difference. */
	private final int[] coveredBefore;

	/**
	 * @param facts    the method's facts
	 * @param siblings the sequence whose runs are slimmed: a statement's kids, in order
	 * @param p        the search's parameters
	 * @param excluded runs left out of every search
	 */
	public RunSlimmer(MethodFacts facts, List<Stmt> siblings, Params p, Set<Run> excluded) {
		this.kids = siblings;
		this.p = p;
		this.excluded = excluded;
		this.lengths = new MethodIndex(facts, p.freeParams());
		this.m = siblings.isEmpty() ? lengths : new MethodIndex(runFacts(facts, lengths, siblings), p.freeParams());
		this.search = new Search(m, p, excluded);
		this.best = new Search.Option[siblings.size()][];
		this.coveredBefore = new int[siblings.size() + 1];
		for (int i = 0; i < siblings.size(); i++) {
			Stmt k = siblings.get(i);
			coveredBefore[i + 1] = coveredBefore[i] + m.lines(k.start(), k.end());
		}
	}

	/** The run's length, as {@link #runFacts} gives it. */
	private int length(int from, int to) {
		return lengths.lines(kids.get(from).start(), kids.get(to - 1).end());
	}

	/** The walk's best option for the run, or null when none fits. */
	private Search.Option bestOf(int from, int to) {
		walk(from);
		Search.Option[] b = best[from];
		return to - from < b.length ? b[to - from] : null;
	}

	/**
	 * A run of sibling statements as a method of its own: its statements the body, its length
	 * their code lines in the method. The method's variables are kept as they are, so what the
	 * run uses from before it is a parameter and what is read after it a result; a field, a loop
	 * and a call to the class's own methods count only inside the run.
	 *
	 * @param index the method's index, for the run's length
	 */
	public static MethodFacts runFacts(MethodFacts facts, MethodIndex index, List<Stmt> run) {
		int lo = run.get(0).start(), hi = run.get(run.size() - 1).end();
		List<Var> vars = new ArrayList<>();
		for (Var v : facts.vars()) {
			if (v.kind() != Var.Kind.FIELD) {
				vars.add(v);
				continue;
			}
			int[] refs = within(v.refs(), lo, hi), defs = within(v.defs(), lo, hi), muts = within(v.muts(), lo, hi);
			if (refs.length + defs.length + muts.length > 0) {
				vars.add(new Var(v.name(), v.kind(), v.first(), v.literal(), refs, defs, muts));
			}
		}
		List<int[]> loops = new ArrayList<>();
		for (int[] l : facts.loops()) {
			if (lo <= l[0] && l[1] <= hi) {
				loops.add(l);
			}
		}
		return new MethodFacts(lo, new Stmt(lo, hi, true, StmtKind.BLOCK, run), vars, loops,
				within(facts.ownCallLines(), lo, hi), index.lines(lo, hi));
	}

	private static int[] within(int[] lines, int lo, int hi) {
		return Arrays.stream(lines).filter(x -> lo <= x && x <= hi).toArray();
	}

	/**
	 * The run {@code siblings[from..to-1]} slimmed as the method {@code runFacts}: the same as
	 * {@code Slimmer.slim(runFacts, p, excluded)}.
	 *
	 * @param runFacts the run as a method of its own; its body's statements are the run's
	 */
	public SlimResult slim(int from, int to, MethodFacts runFacts) {
		if (runFacts == lastFacts && from == lastFrom && to == lastTo) {
			return last; // asked again of the same run (slimmed, then assessed)
		}
		SlimResult r = slimAnew(from, to, runFacts);
		lastFacts = runFacts;
		lastFrom = from;
		lastTo = to;
		last = r;
		return r;
	}

	/** The last run slimmed, and its result. */
	private MethodFacts lastFacts;
	private int lastFrom, lastTo;
	private SlimResult last;

	private SlimResult slimAnew(int from, int to, MethodFacts runFacts) {
		if (runFacts.length() > 0 && theRun(from, to, runFacts)) {
			if (runFacts.length() < p.trigger()) {
				return Slimmer.underTrigger(runFacts.length(), p);
			}
			if (cannot(from, to, runFacts.length())) {
				return Slimmer.result(null, p, runFacts.length(), 0, null);
			}
		}
		MethodIndex rm = new MethodIndex(runFacts, p.freeParams());
		int total = Slimmer.total(runFacts, rm);
		if (total < p.trigger()) {
			return Slimmer.underTrigger(total, p);
		}
		int kidsTotal = new Search(rm, p, excluded).covered(runFacts.body());
		if (!theRun(from, to, runFacts)) {
			return Slimmer.slim(runFacts, p, excluded);
		}
		if (length(from, to) != total || coveredBefore[to] - coveredBefore[from] != kidsTotal) {
			return Slimmer.slim(runFacts, p, excluded);
		}
		return Slimmer.result(rm, p, total, kidsTotal, bestOf(from, to));
	}

	/**
	 * Whether the run {@code siblings[from..to-1]} cannot be slimmed, read from the walk alone,
	 * without the run's facts: the walk leaves no option at its end that brings it to the target.
	 * A run's facts ({@link #runFacts}) index each of its statements as the walk does: code lines
	 * come from the statement tree, and a statement's span holds no line of a sibling but its own
	 * first and last lines, so the lines its search covers are the walk's. Its length is asked
	 * for, and must be the walk's.
	 *
	 * @param length the run's length: its facts' {@code length()}
	 * @return true when {@code slim(from, to, runFacts)} is infeasible; false when it may not be
	 */
	public boolean cannot(int from, int to, int length) {
		if (from < 0 || to > kids.size() || from >= to || length < p.trigger()) {
			return false;
		}
		return length(from, to) == length && bestOf(from, to) == null;
	}

	/** Whether the run's facts are a sequence of exactly these statements. */
	private boolean theRun(int from, int to, MethodFacts runFacts) {
		Stmt body = runFacts.body();
		if (!body.seq() || from < 0 || to > kids.size() || from >= to || body.kids().size() != to - from) {
			return false;
		}
		for (int i = from; i < to; i++) {
			if (body.kids().get(i - from) != kids.get(i)) {
				return false;
			}
		}
		return true;
	}

	/** One walk from {@code from}, keeping the best option at every end it reaches. */
	private void walk(int from) {
		if (best[from] != null) {
			return;
		}
		if (opts == null) {
			opts = search.stmtOptions(kids);
		}
		int n = kids.size();
		// an option at an end brings the run to the target when it leaves at most target + covered
		// - length lines (Slimmer.best): the walk keeps no state over the largest such limit
		int maxRem = Integer.MIN_VALUE;
		for (int end = from + 1; end <= n; end++) {
			int total = length(from, end);
			if (total >= p.trigger()) {
				maxRem = Math.max(maxRem, p.target() + coveredBefore[end] - coveredBefore[from] - total);
			}
		}
		if (maxRem == Integer.MIN_VALUE) {
			best[from] = new Search.Option[0]; // every run from here is under the trigger
			return;
		}
		List<Search.Option> bests = new ArrayList<>();
		bests.add(null); // the empty run
		search.pass(kids, opts, from, maxRem, (end, states) -> {
			int total = length(from, end);
			bests.add(total < p.trigger() ? null
					: Slimmer.best(Search.pareto(new ArrayList<>(states.values())), p, total,
							coveredBefore[end] - coveredBefore[from]));
		});
		// an end the walk did not reach has no state left that reaches the target: none fits
		best[from] = bests.toArray(new Search.Option[0]);
	}
}
