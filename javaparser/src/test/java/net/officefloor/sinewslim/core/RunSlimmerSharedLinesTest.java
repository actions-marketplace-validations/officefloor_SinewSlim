package net.officefloor.sinewslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Stmt;
import net.officefloor.sinewslim.javaparser.JavaFacts;
import net.officefloor.sinewslim.javaparser.MethodUnit;
import net.officefloor.sinewslim.javaparser.ParsedFile;

/**
 * {@link RunSlimmer}'s walks, which drop the states that can no longer reach the target, slim
 * every run as the whole search does, on methods whose loops and ifs hold several statements to
 * a line (mixing code, cipher rounds: {@code a ^= b << 11; d += a; b += c;}). Those statements'
 * lines are each counted against one shared line, so a statement slimmed from inside can leave
 * fewer than no lines, and the lines left fall later in a walk. SinewSlim 0.2.2 pruned as if they
 * could not, and slimmed two such methods of a study's sample differently; this generates many,
 * checks they have such statements, and that no run differs, under the defaults and the
 * structural variant (the two the walks are used with); and that a method's own search, which
 * prunes too, slims each as the whole search, under every variant the assessment uses.
 */
class RunSlimmerSharedLinesTest {

	static boolean negative(Search s, Stmt node) {
		for (Stmt k : node.kids()) {
			if (!k.seq()) {
				for (Search.Option o : s.solveStmt(k)) {
					if (o.rem() < 0) {
						return true;
					}
				}
			}
			if (negative(s, k)) {
				return true;
			}
		}
		return false;
	}

	static final String[] VARS = { "a", "b", "c", "d", "e" };

	static String var(Random r) {
		return VARS[r.nextInt(VARS.length)];
	}

	/**
	 * A line of one to four simple statements, all updating {@code to} (so a piece of them returns
	 * one value, as the defaults allow), now and then another variable.
	 */
	static String line(Random r, String indent, String to) {
		StringBuilder b = new StringBuilder(indent);
		int k = 1 + r.nextInt(4);
		for (int i = 0; i < k; i++) {
			b.append(i == 0 ? "" : " ").append(r.nextInt(8) == 0 ? var(r) : to).append(r.nextBoolean() ? " ^= " : " += ")
					.append(var(r)).append(r.nextBoolean() ? " << " : " >>> ").append(1 + r.nextInt(20)).append(';');
		}
		return b.append('\n').toString();
	}

	/** A block of lines, now and then a loop or an if of its own, to {@code depth}. */
	static void block(Random r, StringBuilder b, String indent, int lines, int depth) {
		String to = var(r);
		for (int i = 0; i < lines; i++) {
			int pick = depth > 0 ? r.nextInt(6) : 0;
			if (pick == 1) {
				b.append(indent).append("for (int i").append(depth).append(" = 0; i").append(depth).append(" < 8; i")
						.append(depth).append("++) {\n");
				block(r, b, indent + "\t", 2 + r.nextInt(8), depth - 1);
				b.append(indent).append("}\n");
			} else if (pick == 2) {
				b.append(indent).append("if (").append(var(r)).append(" > ").append(r.nextInt(99)).append(") {\n");
				block(r, b, indent + "\t", 2 + r.nextInt(8), depth - 1);
				b.append(indent).append("}\n");
			} else {
				b.append(line(r, indent, to));
			}
		}
	}

	/** {@code Slimmer.slim}, with the whole search: no state dropped. */
	static SlimResult unpruned(MethodFacts facts, Params p) {
		MethodIndex m = new MethodIndex(facts, p.freeParams());
		int total = Slimmer.total(facts, m);
		if (total < p.trigger()) {
			return Slimmer.underTrigger(total, p);
		}
		Search search = new Search(m, p, Set.of());
		int kidsTotal = search.covered(facts.body());
		return Slimmer.result(m, p, total, kidsTotal, Slimmer.best(search.solveSeq(facts.body()), p, total, kidsTotal));
	}

	/** A generated method with shared lines: its source. */
	static String source(Random r) {
		StringBuilder body = new StringBuilder();
		block(r, body, "\t\t", 8 + r.nextInt(30), 3);
		return "class A {\n\tint f(int a, int b, int c, int d, int e) {\n" + body + "\t\treturn a + b + c + d + e;\n\t}\n}\n";
	}

	/**
	 * A method's own search, which drops the states that can no longer reach the target too,
	 * slims as the whole search, under the defaults and every variant the assessment searches
	 * when it cannot slim a method (the obstacle: records, unlimited inputs, both, jumps ignored),
	 * the structural one and the study's.
	 */
	@Test
	void aMethodIsSlimmedAsByTheWholeSearchWhereStatementsShareLines() {
		Params base = Assessor.DEFAULTS.params();
		Params unlimited = base.toBuilder().maxInputs(-1).maxOutputs(-1).build();
		List<Params> variants = List.of(base, base.records(), base.unlimitedInputs(), unlimited,
				unlimited.toBuilder().ignoreJumps(true).build(), base.structural(), Params.STUDY);
		Random r = new Random(20261104);
		int methods = 0, withNegative = 0;
		List<String> mismatches = new ArrayList<>();
		for (int t = 0; t < 300; t++) {
			String source = source(r);
			ParsedFile f = JavaFacts.parse("A.java", source);
			assertTrue(f.parsed(), f.problem());
			MethodFacts facts = f.methods().get(0).facts();
			methods++;
			if (negative(new Search(new MethodIndex(facts, false), base, Set.of()), facts.body())
					|| negative(new Search(new MethodIndex(facts, false), unlimited, Set.of()), facts.body())) {
				withNegative++;
			}
			for (Params p : variants) {
				SlimResult want = unpruned(facts, p), got = Slimmer.slim(facts, p, Set.of());
				if (!want.equals(got) && mismatches.size() < 5) {
					mismatches.add(p + ": " + got + " != " + want + "\n" + source);
				}
			}
		}
		assertEquals(List.of(), mismatches);
		assertTrue(withNegative > 100, "too few methods with an option leaving fewer than no lines: " + withNegative);
		System.out.println("RunSlimmerSharedLinesTest: " + methods + " methods (" + withNegative
				+ " with an option leaving fewer than no lines), each under " + variants.size() + " variants: all as the whole search");
	}

	@Test
	void everyRunIsSlimmedAsFromScratchWhereStatementsShareLines() {
		Params base = Assessor.DEFAULTS.params();
		List<Params> variants = List.of(base, base.structural());
		Random r = new Random(20261103);
		int methods = 0, withNegative = 0, runs = 0;
		List<String> mismatches = new ArrayList<>();
		for (int t = 0; t < 120; t++) {
			StringBuilder body = new StringBuilder();
			block(r, body, "\t\t", 8 + r.nextInt(30), 3);
			String source = "class A {\n\tint f(int a, int b, int c, int d, int e) {\n" + body + "\t\treturn a + b + c + d + e;\n\t}\n}\n";
			ParsedFile f = JavaFacts.parse("A.java", source);
			assertTrue(f.parsed(), f.problem());
			MethodUnit u = f.methods().get(0);
			MethodFacts facts = u.facts();
			methods++;
			if (negative(new Search(new MethodIndex(facts, false), base, Set.of()), facts.body())) {
				withNegative++;
			}
			// the runs of the top sequence, walked together, as slimmed from scratch
			List<Stmt> kids = facts.body().kids();
			MethodIndex index = new MethodIndex(facts, base.freeParams());
			for (Params p : variants) {
				RunSlimmer walked = new RunSlimmer(facts, kids, p, Set.of());
				for (int from = 0; from < kids.size(); from++) {
					for (int to = from + 1; to <= kids.size(); to++) {
						MethodFacts rf = RunSlimmer.runFacts(facts, index, kids.subList(from, to));
						runs++;
						SlimResult want = Slimmer.slim(rf, p, Set.of()), got = walked.slim(from, to, rf);
						if (!want.equals(got) && mismatches.size() < 5) {
							mismatches.add("run [" + from + "," + to + ") " + p + ": " + got + " != " + want + "\n" + source);
						}
						if (walked.cannot(from, to, rf.length()) && want.feasible() && mismatches.size() < 5) {
							mismatches.add("cannot [" + from + "," + to + ") " + p + ": " + want + "\n" + source);
						}
					}
				}
			}
		}
		assertEquals(List.of(), mismatches);
		assertTrue(withNegative > 30, "too few methods with an option leaving fewer than no lines: " + withNegative);
		System.out.println("RunSlimmerSharedLinesTest: " + methods + " methods (" + withNegative
				+ " with an option leaving fewer than no lines), " + runs + " runs: all as from scratch");
	}
}
