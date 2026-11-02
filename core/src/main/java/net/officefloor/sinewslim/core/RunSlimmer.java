package net.officefloor.sinewslim.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	/** By the run's first statement, then by its end: the best option, or null when none fits. */
	private final Map<Integer, Map<Integer, Search.Option>> best = new HashMap<>();

	/** The same, the run's length (as {@link #runFacts} gives it) and the lines its search covers. */
	private final Map<Integer, Map<Integer, int[]>> sizes = new HashMap<>();

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
		MethodIndex rm = new MethodIndex(runFacts, p.freeParams());
		int total = Slimmer.total(runFacts, rm);
		if (total < p.trigger()) {
			return Slimmer.underTrigger(total, p);
		}
		int kidsTotal = new Search(rm, p, excluded).covered(runFacts.body());
		if (!theRun(from, to, runFacts)) {
			return Slimmer.slim(runFacts, p, excluded);
		}
		walk(from);
		int[] size = sizes.get(from).get(to);
		if (size[0] != total || size[1] != kidsTotal) {
			return Slimmer.slim(runFacts, p, excluded);
		}
		return Slimmer.result(rm, p, total, kidsTotal, best.get(from).get(to));
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

	/** One walk from {@code from}, keeping the best option at every end. */
	private void walk(int from) {
		if (best.containsKey(from)) {
			return;
		}
		if (opts == null) {
			opts = search.stmtOptions(kids);
		}
		Map<Integer, Search.Option> bests = new HashMap<>();
		Map<Integer, int[]> lens = new HashMap<>();
		int lo = kids.get(from).start();
		int[] covered = { 0 };
		search.pass(kids, opts, from, (end, states) -> {
			Stmt last = kids.get(end - 1);
			covered[0] += m.lines(last.start(), last.end());
			int total = lengths.lines(lo, last.end());
			lens.put(end, new int[] { total, covered[0] });
			if (total >= p.trigger()) {
				bests.put(end, Slimmer.best(Search.pareto(new ArrayList<>(states.values())), p, total, covered[0]));
			}
		});
		best.put(from, bests);
		sizes.put(from, lens);
	}
}
