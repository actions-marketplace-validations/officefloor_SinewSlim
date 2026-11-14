package net.officefloor.sinewslim.facts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** A method's facts indexed for the many seams a search asks for ({@code split.py}, Method). */
public final class MethodIndex {

	/** A variable's lines, with its literal declaration line (or -1) and every line it is touched on. */
	record IndexedVar(Var var, int decl, int[] touches) {
	}

	private final MethodFacts facts;
	private final boolean freeParams;
	private final int[] code;
	private final List<IndexedVar> vars = new ArrayList<>();
	private final Map<Long, Seam> seams = new HashMap<>();

	/**
	 * @param facts      the method's facts
	 * @param freeParams a parameter handed to a run unchanged is not one of its inputs: the
	 *                   framework injects it (it is still listed as passed)
	 */
	public MethodIndex(MethodFacts facts, boolean freeParams) {
		this.facts = facts;
		this.freeParams = freeParams;
		TreeSet<Integer> lines = new TreeSet<>();
		treeLines(facts.body(), lines);
		for (int l = facts.declLine(); l <= facts.body().start(); l++) {
			lines.add(l); // the signature
		}
		code = lines.stream().mapToInt(Integer::intValue).toArray();
		for (Var v : facts.vars()) {
			int decl = -1;
			if (v.literal() && contains(v.defs(), v.first()) && !contains(v.refs(), v.first())) {
				decl = v.first(); // a literal initializer: a definition, not a use
			}
			TreeSet<Integer> touches = new TreeSet<>();
			for (int[] xs : new int[][] { v.refs(), v.defs(), v.muts() }) {
				for (int x : xs) {
					touches.add(x);
				}
			}
			vars.add(new IndexedVar(v, decl, touches.stream().mapToInt(Integer::intValue).toArray()));
		}
	}

	/**
	 * The code lines a statement tree covers: a leaf statement's whole span, a compound
	 * statement's span less its blocks (its header and closing lines), a block's first and last
	 * lines (its braces) and its statements.
	 */
	static void treeLines(Stmt node, TreeSet<Integer> out) {
		if (node.seq()) {
			out.add(node.start());
			out.add(node.end());
			for (Stmt k : node.kids()) {
				treeLines(k, out);
			}
			return;
		}
		TreeSet<Integer> own = new TreeSet<>();
		for (int l = node.start(); l <= node.end(); l++) {
			own.add(l);
		}
		for (Stmt k : node.kids()) {
			if (k.seq()) {
				own.subSet(k.start(), true, k.end(), true).clear();
			}
		}
		out.addAll(own);
		for (Stmt k : node.kids()) {
			treeLines(k, out);
		}
	}

	public MethodFacts facts() {
		return facts;
	}

	/** The method's code lines: those its statement tree and its signature cover. */
	public int codeLines() {
		return code.length;
	}

	/** The code lines in [lo, hi]. */
	public int lines(int lo, int hi) {
		return bisectRight(code, hi) - bisectLeft(code, lo);
	}

	/** What crosses the boundary of the run of lines [lo, hi]. */
	public Seam seam(int lo, int hi) {
		return seams.computeIfAbsent(((long) lo << 32) | (hi & 0xffffffffL), k -> computeSeam(lo, hi));
	}

	private Seam computeSeam(int lo, int hi) {
		List<String> inputs = new ArrayList<>(), outputs = new ArrayList<>(), moved = new ArrayList<>(),
				fread = new ArrayList<>(), fwrit = new ArrayList<>(), passed = new ArrayList<>();
		List<int[]> enclosing = new ArrayList<>();
		for (int[] loop : facts.loops()) {
			if (loop[0] < lo && hi <= loop[1]) {
				enclosing.add(loop);
			}
		}
		for (IndexedVar iv : vars) {
			Var v = iv.var();
			int[] refs = v.refs(), defs = v.defs(), muts = v.muts();
			if (v.kind() == Var.Kind.FIELD) {
				if (any(refs, lo, hi) || any(defs, lo, hi) || any(muts, lo, hi)) {
					if (any(defs, lo, hi) || any(muts, lo, hi)) {
						fwrit.add(v.name());
					} else {
						fread.add(v.name());
					}
				}
				continue;
			}
			if (!anyBut(iv.touches(), lo, hi, iv.decl())) {
				continue;
			}
			boolean before = anyBelow(refs, lo) || anyBelow(muts, lo) || anyBelowBut(defs, lo, iv.decl());
			if (!before) {
				// declared before an enclosing loop and written in it: the run reads the value the previous
				// iteration left, so a literal declaration cannot move into the piece across the back edge
				for (int[] loop : enclosing) {
					if (v.first() < loop[0] && any(defs, loop[0], loop[1])) {
						before = true;
						break;
					}
				}
			}
			boolean after = anyAbove(iv.touches(), hi);
			boolean written = any(defs, lo, hi);
			if (v.kind() == Var.Kind.PARAM && !anyBelow(defs, lo) && !anyBelow(muts, lo)) {
				passed.add(v.name()); // handed on as it came
				if (!freeParams) {
					inputs.add(v.name());
				}
			} else if (v.kind() == Var.Kind.PARAM || before) {
				inputs.add(v.name());
			}
			boolean backEdge = false;
			if (written) {
				for (int[] loop : enclosing) {
					if (any(refs, loop[0], lo - 1)) {
						backEdge = true;
						break;
					}
				}
			}
			if (written && (after || backEdge)) {
				outputs.add(v.name());
			} else if (iv.decl() >= 0 && iv.decl() < lo && !before && !after) {
				moved.add(v.name());
			}
		}
		int[] own = facts.ownCallLines();
		int ownCalls = bisectRight(own, hi) - bisectLeft(own, lo);
		return new Seam(lo, hi, inputs, outputs, moved, fread, fwrit, ownCalls, passed);
	}

	/**
	 * Whether what stays behind writes a field or calls the class's own methods outside the cuts.
	 *
	 * @param lockOnAnyField a field read outside the cuts binds it too
	 */
	public boolean remainderLocked(List<Seam> cuts, boolean lockOnAnyField) {
		for (int x : facts.ownCallLines()) {
			if (outside(x, cuts)) {
				return true;
			}
		}
		for (IndexedVar iv : vars) {
			Var v = iv.var();
			if (v.kind() != Var.Kind.FIELD) {
				continue;
			}
			for (int[] xs : new int[][] { v.defs(), v.muts() }) {
				for (int x : xs) {
					if (outside(x, cuts)) {
						return true;
					}
				}
			}
			if (lockOnAnyField) {
				for (int x : v.refs()) {
					if (outside(x, cuts)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean outside(int line, List<Seam> cuts) {
		for (Seam c : cuts) {
			if (c.lo() <= line && line <= c.hi()) {
				return false;
			}
		}
		return true;
	}

	static boolean contains(int[] xs, int x) {
		return Arrays.binarySearch(xs, x) >= 0;
	}

	static int bisectLeft(int[] xs, int x) {
		int lo = 0, hi = xs.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (xs[mid] < x) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	static int bisectRight(int[] xs, int x) {
		int lo = 0, hi = xs.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (xs[mid] <= x) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	static boolean any(int[] xs, int lo, int hi) {
		int i = bisectLeft(xs, lo);
		return i < xs.length && xs[i] <= hi;
	}

	static boolean anyBut(int[] xs, int lo, int hi, int skip) {
		for (int i = bisectLeft(xs, lo); i < xs.length && xs[i] <= hi; i++) {
			if (xs[i] != skip) {
				return true;
			}
		}
		return false;
	}

	static boolean anyBelow(int[] xs, int lo) {
		return xs.length > 0 && xs[0] < lo;
	}

	static boolean anyBelowBut(int[] xs, int lo, int skip) {
		for (int x : xs) {
			if (x >= lo) {
				return false;
			}
			if (x != skip) {
				return true;
			}
		}
		return false;
	}

	static boolean anyAbove(int[] xs, int hi) {
		return xs.length > 0 && xs[xs.length - 1] > hi;
	}
}
