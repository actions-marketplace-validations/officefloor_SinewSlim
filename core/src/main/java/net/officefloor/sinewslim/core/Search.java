package net.officefloor.sinewslim.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.officefloor.sinewslim.facts.Jumps;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Seam;
import net.officefloor.sinewslim.facts.Stmt;

/**
 * The bottom-up dynamic program over the statement tree ({@code split.py}, the search). For each
 * sequence, walk its statements in order; at each, either keep it in the remainder (as it is, or
 * reduced by extractions inside it) or close an extracted run ending there. The iteration orders
 * are {@code split.py}'s, so ties fall the same way.
 */
final class Search {

	/**
	 * One way of handling a stretch of statements: the lines left in place (each extracted piece
	 * replaced by one call line), the worst seam, the total cost, the pieces and the cuts.
	 */
	record Option(int rem, int width, int cost, int pieces, List<Seam> cuts) {

		static Option of(int rem) {
			return new Option(rem, 0, 0, 0, List.of());
		}

		/** The objective: the worst seam, then the fewest pieces, then the total cost. */
		boolean better(Option o) {
			if (width != o.width) {
				return width < o.width;
			}
			if (pieces != o.pieces) {
				return pieces < o.pieces;
			}
			return cost < o.cost;
		}

		Option shift(int by) {
			return new Option(rem + by, width, cost, pieces, cuts);
		}
	}

	static final Comparator<Option> PARETO_ORDER = Comparator.comparingInt(Option::rem).thenComparingInt(Option::width)
			.thenComparingInt(Option::pieces).thenComparingInt(Option::cost);

	private final MethodIndex m;
	private final Params p;
	private final Set<Run> excluded;

	Search(MethodIndex m, Params p, Set<Run> excluded) {
		this.m = m;
		this.p = p;
		this.excluded = excluded;
	}

	static List<Seam> concat(List<Seam> a, List<Seam> b) {
		if (b.isEmpty()) {
			return a;
		}
		if (a.isEmpty()) {
			return b;
		}
		List<Seam> out = new ArrayList<>(a.size() + b.size());
		out.addAll(a);
		out.addAll(b);
		return out;
	}

	static List<Seam> append(List<Seam> a, Seam s) {
		List<Seam> out = new ArrayList<>(a.size() + 1);
		out.addAll(a);
		out.add(s);
		return out;
	}

	static Option combine(Option a, Option b) {
		return new Option(a.rem + b.rem, Math.max(a.width, b.width), a.cost + b.cost, a.pieces + b.pieces,
				concat(a.cuts, b.cuts));
	}

	/** Every option no other beats on all of lines left, worst seam, pieces and cost. */
	static List<Option> pareto(List<Option> options) {
		List<Option> opts = new ArrayList<>(options);
		opts.sort(PARETO_ORDER); // stable, as Python's sorted
		List<Option> keep = new ArrayList<>();
		for (Option o : opts) {
			boolean dominated = false;
			for (Option k : keep) {
				if (k.rem <= o.rem && k.width <= o.width && k.pieces <= o.pieces && k.cost <= o.cost) {
					dominated = true;
					break;
				}
			}
			if (!dominated) {
				keep.add(o);
			}
		}
		return keep;
	}

	static void push(Map<Integer, Option> states, Option o) {
		Option cur = states.get(o.rem);
		if (cur == null || o.better(cur)) {
			states.put(o.rem, o); // an existing key keeps its place, as a Python dict's does
		}
	}

	/**
	 * The lines an extracted piece leaves behind: its call, and with a record, the unpacking of
	 * each value when that is counted.
	 */
	int left(Seam s) {
		int k = s.outputs().size();
		return 1 + (p.countUnpacking() && k > 1 ? k : 0);
	}

	/** The lines a node's own search accounts for: a sequence's statements, a statement's span. */
	int covered(Stmt node) {
		if (node.seq()) {
			int sum = 0;
			for (Stmt k : node.kids()) {
				sum += m.lines(k.start(), k.end());
			}
			return sum;
		}
		return m.lines(node.start(), node.end());
	}

	/** The options for one statement: as it is, or reduced by extractions inside it. */
	List<Option> solveStmt(Stmt node) {
		int total = m.lines(node.start(), node.end());
		List<Option> opts = List.of(Option.of(total));
		for (Stmt c : node.kids()) {
			List<Option> f = c.seq() ? solveSeq(c) : solveStmt(c);
			// a child's saving is read against what its own search covered: its statements' lines
			// for a sequence (its braces stay), its whole span for a statement
			int base = covered(c);
			List<Option> combined = new ArrayList<>(opts.size() * f.size());
			for (Option o : opts) {
				for (Option d : f) {
					combined.add(combine(o, d.shift(-base)));
				}
			}
			opts = pareto(combined);
		}
		return opts;
	}

	/** The Pareto front of ways to handle a sequence: lines left, worst seam, cost, pieces. */
	List<Option> solveSeq(Stmt node) {
		return solveSeq(node, Integer.MAX_VALUE);
	}

	/**
	 * The same front less the options that leave more than {@code maxRem} lines, and less the
	 * states that could reach none that leave fewer ({@link #pass}): for a caller that takes only an
	 * option leaving at most that many ({@link Slimmer#slim}, a method's own body).
	 */
	List<Option> solveSeq(Stmt node, int maxRem) {
		List<Stmt> kids = node.kids();
		List<List<Option>> opts = stmtOptions(kids);
		List<Map<Integer, Option>> at = pass(kids, opts, 0, maxRem, null);
		return pareto(new ArrayList<>(at.get(kids.size()).values()));
	}

	/** Each statement's options, as it is or reduced from inside ({@link #solveStmt}). */
	List<List<Option>> stmtOptions(List<Stmt> kids) {
		List<List<Option>> opts = new ArrayList<>(kids.size());
		for (Stmt k : kids) {
			opts.add(solveStmt(k));
		}
		return opts;
	}

	/** Told the states after each statement of a pass, once they are complete. */
	interface EndListener {

		/**
		 * @param end    the pass has handled the statements before this index
		 * @param states the ways of handling them, by lines left
		 */
		void states(int end, Map<Integer, Option> states);
	}

	/**
	 * The left-to-right walk of a sequence from the statement at {@code from}: the states after
	 * each statement, the run from {@code from} handled as a sequence of its own. The states after
	 * {@code kids[from..i-1]} are the same whatever follows, so one pass serves every run that
	 * starts at {@code from}. Returned by offset from {@code from}.
	 *
	 * @param opts  each statement's own options ({@link #stmtOptions})
	 * @param onEnd told each end's states as they are complete; may be null
	 */
	List<Map<Integer, Option>> pass(List<Stmt> kids, List<List<Option>> opts, int from, EndListener onEnd) {
		return pass(kids, opts, from, Integer.MAX_VALUE, onEnd);
	}

	/**
	 * The same walk, keeping only the states that can still end at {@code maxRem} lines left or
	 * fewer. A later statement adds its option's lines left, which is negative when it is slimmed
	 * from inside by more than its own length counts (a loop or if whose body's statements are
	 * cut); a cut adds its call, at least one. So the lines left can fall after statement
	 * {@code q} by at most the sum of the later statements' most negative options, and a state
	 * that leaves more than {@code maxRem} plus that fall, and every state built from it, leaves
	 * too many at every later end: for a caller that asks only which states reach that limit there
	 * ({@link Slimmer#slim}, {@link RunSlimmer}), dropping them changes nothing. Nor can a dropped
	 * state hide a kept one from {@link #pareto} at an end, as it leaves more lines than any that
	 * reaches the limit. Once no state is left that a later statement could build on, the walk
	 * ends: the ends after it have no states, and are not told. A sequence with no negative option
	 * (a flat table) is pruned at {@code maxRem} itself.
	 */
	List<Map<Integer, Option>> pass(List<Stmt> kids, List<List<Option>> opts, int from, int maxRem, EndListener onEnd) {
		int n = kids.size();
		List<Map<Integer, Option>> at = new ArrayList<>(n - from + 1);
		for (int i = from; i <= n; i++) {
			at.add(new LinkedHashMap<>());
		}
		at.get(0).put(0, Option.of(0));
		// the most the lines left can still fall after the statements before each index
		long[] fall = new long[n + 1];
		for (int k = n - 1; k >= from; k--) {
			int least = 0;
			for (Option o : opts.get(k)) {
				least = Math.min(least, o.rem);
			}
			fall[k] = fall[k + 1] - least;
		}
		int live = from; // the last statement a state comes before: where a later run could start
		for (int i = from; i < n; i++) {
			Map<Integer, Option> next = at.get(i + 1 - from);
			int limit = (int) Math.min(Integer.MAX_VALUE, (long) maxRem + fall[i + 1]);
			for (Option st : at.get(i - from).values()) {
				for (Option o : opts.get(i)) {
					push(next, combine(st, o), limit);
				}
			}
			Stmt kid = kids.get(i);
			if (!p.structuralOnly() || kid.kind().structural()) {
				// a single statement, as it is or reduced from inside, then extracted whole
				if (p.ignoreJumps() || Jumps.ok(List.of(kid), p.trailingReturn())) {
					Seam sm = m.seam(kid.start(), kid.end());
					if (p.feasible(sm) && !excluded.contains(Run.of(sm))) {
						int w = p.width(sm), c = p.cost(sm);
						for (Option o : opts.get(i)) {
							if (p.minPiece() <= o.rem && o.rem <= p.target() && o.rem - 1 >= p.minSaving()) {
								for (Option st : List.copyOf(at.get(i - from).values())) {
									push(next, new Option(st.rem + left(sm), Math.max(Math.max(st.width, o.width), w),
											st.cost + o.cost + c, st.pieces + o.pieces + 1,
											append(concat(st.cuts, o.cuts), sm)), limit);
								}
							}
						}
					}
				}
			}
			if (!p.structuralOnly()) {
				// a run of several statements, as they are
				for (int j = i - 1; j >= from; j--) {
					int lines = m.lines(kids.get(j).start(), kid.end());
					if (lines > p.target()) {
						break;
					}
					if (lines < p.minPiece() || lines - 1 < p.minSaving() || at.get(j - from).isEmpty()) {
						continue; // too short, or no state to cut it from
					}
					if (!p.ignoreJumps() && !Jumps.ok(kids.subList(j, i + 1), p.trailingReturn())) {
						continue;
					}
					Seam sm = m.seam(kids.get(j).start(), kid.end());
					if (!p.feasible(sm) || excluded.contains(Run.of(sm))) {
						continue;
					}
					int w = p.width(sm), c = p.cost(sm);
					for (Option st : at.get(j - from).values()) {
						push(next, new Option(st.rem + left(sm), Math.max(st.width, w), st.cost + c, st.pieces + 1,
								append(st.cuts, sm)), limit);
					}
				}
			}
			if (onEnd != null) {
				onEnd.states(i + 1, next);
			}
			if (!next.isEmpty()) {
				live = i + 1;
			} else if (live < n && m.lines(kids.get(live).start(), kid.end()) > p.target()) {
				// every later state is built from one before a statement no later than live, by a
				// run from there; any such run is now over the target
				break;
			}
		}
		return at;
	}

	private static void push(Map<Integer, Option> states, Option o, int maxRem) {
		if (o.rem <= maxRem) {
			push(states, o);
		}
	}
}
