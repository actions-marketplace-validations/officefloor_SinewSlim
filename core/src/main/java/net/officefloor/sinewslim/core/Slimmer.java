package net.officefloor.sinewslim.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Seam;

/**
 * Finds the cheapest set of contiguous statement runs whose extraction brings a method, and every
 * piece, to the target length ({@code SPLIT.md}; {@code split.py}, split).
 *
 * <p>
 * The search recurses once or twice per level of the statement tree, so a method with a long
 * else-if chain needs a deep stack; run it on a thread with a generous stack size.
 */
public final class Slimmer {

	private Slimmer() {
	}

	/** The order cuts are reported in: {@code split.py} sorts its (lo, hi, inputs, outputs, binding) tuples. */
	private static Comparator<Seam> cutOrder(Params p) {
		return Comparator.comparingInt(Seam::lo).thenComparingInt(Seam::hi)
				.thenComparingInt((Seam s) -> s.inputs().size()).thenComparingInt(s -> s.outputs().size())
				.thenComparing(s -> p.binding(s).name().toLowerCase());
	}

	public static SlimResult slim(MethodFacts facts, Params p) {
		return slim(facts, p, Set.of());
	}

	/** The best segmentation that uses none of the {@code excluded} runs: the pieces the developer skipped. */
	public static SlimResult slim(MethodFacts facts, Params p, Set<Run> excluded) {
		MethodIndex m = new MethodIndex(facts, p.freeParams());
		int total = total(facts, m);
		if (total < p.trigger()) {
			return underTrigger(total, p);
		}
		Search search = new Search(m, p, excluded);
		// what the search removes is read against the statements it sees; the lines no statement
		// covers (the signature, a closing brace) stay whatever is cut
		int kidsTotal = search.covered(facts.body());
		// best takes only an option leaving at most target + kidsTotal - total lines: the search
		// keeps no state that, allowing for how far later statements can lower it, leaves more
		// (Search.pass)
		return result(m, p, total, kidsTotal,
				best(search.solveSeq(facts.body(), p.target() + kidsTotal - total), p, total, kidsTotal));
	}

	/** The method's length: its counted length, else its code lines. */
	static int total(MethodFacts facts, MethodIndex m) {
		return facts.length() > 0 ? facts.length() : m.codeLines();
	}

	/** Under the trigger: nothing to slim. */
	static SlimResult underTrigger(int total, Params p) {
		return new SlimResult(false, true, 0, 0, total, List.of(), List.of(), false, p);
	}

	/**
	 * The best of a sequence's options that brings the method to the target, or null.
	 *
	 * @param kidsTotal the lines the search of the sequence covered ({@link Search#covered})
	 */
	static Search.Option best(List<Search.Option> options, Params p, int total, int kidsTotal) {
		Search.Option best = null;
		for (Search.Option o : options) {
			if (total - (kidsTotal - o.rem()) <= p.target() && (best == null || o.better(best))) {
				best = o;
			}
		}
		return best;
	}

	/** The result of the {@code best} option, read against the method's own index; infeasible when null. */
	static SlimResult result(MethodIndex m, Params p, int total, int kidsTotal, Search.Option best) {
		if (best == null) {
			return new SlimResult(true, false, SlimResult.INFEASIBLE, SlimResult.INFEASIBLE, total, List.of(), List.of(),
					false, p);
		}
		List<Seam> cuts = new ArrayList<>(best.cuts());
		cuts.sort(cutOrder(p));
		return new SlimResult(true, true, best.width(), best.cost(), total - (kidsTotal - best.rem()), cuts,
				pieceLines(m, cuts), m.remainderLocked(cuts, p.lockOnAnyField()), p);
	}

	/**
	 * Each piece's code lines once the pieces directly inside it are out, each of those leaving
	 * one call line behind.
	 */
	static List<Integer> pieceLines(MethodIndex m, List<Seam> cuts) {
		List<Integer> out = new ArrayList<>(cuts.size());
		for (Seam outer : cuts) {
			int lines = m.lines(outer.lo(), outer.hi());
			for (Seam inner : cuts) {
				if (inner != outer && inside(inner, outer) && directlyInside(inner, outer, cuts)) {
					lines -= m.lines(inner.lo(), inner.hi()) - 1;
				}
			}
			out.add(lines);
		}
		return out;
	}

	private static boolean inside(Seam inner, Seam outer) {
		return outer.lo() <= inner.lo() && inner.hi() <= outer.hi() && !(outer.lo() == inner.lo() && outer.hi() == inner.hi());
	}

	private static boolean directlyInside(Seam inner, Seam outer, List<Seam> cuts) {
		for (Seam mid : cuts) {
			if (mid != inner && mid != outer && inside(inner, mid) && inside(mid, outer)) {
				return false;
			}
		}
		return true;
	}
}
