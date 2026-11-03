package net.officefloor.sinewslim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import net.officefloor.sinewslim.facts.MethodFacts;
import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Stmt;
import net.officefloor.sinewslim.facts.StmtKind;

/**
 * A run slimmed from one shared walk is the run slimmed from scratch: on every method of the
 * committed parity subset, every run of every sequence not inside a loop, as a method of its own.
 */
class RunSlimmerTest {

	/** Sequences longer than this are sampled: every run from every third statement. */
	static final int EXHAUSTIVE = 40;

	/** Every sequence of the tree not inside a loop. */
	static void sequences(Stmt node, List<Stmt> out) {
		if (node.kind() == StmtKind.LOOP) {
			return;
		}
		if (node.seq() && !node.kids().isEmpty()) {
			out.add(node);
		}
		for (Stmt k : node.kids()) {
			sequences(k, out);
		}
	}

	@Test
	void everyRunOfTheParitySubsetIsSlimmedAsFromScratch() throws IOException {
		InputStream in = RunSlimmerTest.class.getResourceAsStream(StudyParityTest.SUBSET);
		assertTrue(in != null);
		int methods = 0, runs = 0, slimmed = 0, cannot = 0;
		List<String> mismatches = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
			r.readLine(); // the parameters
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				MethodFacts facts = StudyParityTest.facts(StudyParityTest.JSON.readTree(line));
				methods++;
				MethodIndex index = new MethodIndex(facts, Assessor.DEFAULTS.params().freeParams());
				List<Stmt> seqs = new ArrayList<>();
				sequences(facts.body(), seqs);
				for (Stmt seq : seqs) {
					List<Stmt> kids = seq.kids();
					int n = kids.size(), step = n > EXHAUSTIVE ? 3 : 1;
					Assessor.Runs fast = Assessor.DEFAULTS.runs(facts, kids);
					for (int from = 0; from < n; from += step) {
						for (int to = from + 1; to <= n; to += step) {
							MethodFacts rf = RunSlimmer.runFacts(facts, index, kids.subList(from, to));
							runs++;
							SlimResult want = Slimmer.slim(rf, Assessor.DEFAULTS.params(), Set.of());
							SlimResult got = fast.slim(from, to, rf);
							if (fast.cannot(from, to, rf.length())) {
								cannot++;
								if (want.feasible() && mismatches.size() < 10) {
									mismatches.add("cannot " + facts.declLine() + " [" + from + "," + to + "): " + want);
								}
							}
							if (!want.equals(got) && mismatches.size() < 10) {
								mismatches.add("slim " + facts.declLine() + " [" + from + "," + to + "): " + got + " != " + want);
							}
							if (want.applicable() && want.feasible()) {
								slimmed++;
								Assessment a = Assessor.DEFAULTS.assess(rf), b = fast.assess(from, to, rf);
								if (!a.equals(b) && mismatches.size() < 10) {
									mismatches.add("assess " + facts.declLine() + " [" + from + "," + to + "): " + b + " != " + a);
								}
							}
						}
					}
				}
			}
		}
		assertEquals(List.of(), mismatches, methods + " methods, " + runs + " runs, " + slimmed + " slimmed");
		assertTrue(slimmed > 1000, "too few runs slimmed to show anything: " + slimmed + " of " + runs);
		assertTrue(cannot > 1000, "too few runs known infeasible from the walk to show anything: " + cannot);
		System.out.println("RunSlimmerTest: " + methods + " methods, " + runs + " runs, " + slimmed + " slimmed, " + cannot
				+ " known infeasible from the walk: all as from scratch");
	}
}
