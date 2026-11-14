package net.officefloor.sinewslim.javaparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.officefloor.sinewslim.facts.MethodIndex;
import net.officefloor.sinewslim.facts.Seam;

/**
 * A run inside a loop reads the value the previous iteration left, so a local declared before the loop
 * with a literal and written in the loop is an input of the run: the literal declaration cannot move into
 * the piece across the back edge. Without the loop, the literal rule still moves it.
 */
class LoopSeamTest {

	/** The seam of the run of lines [lo, hi] of method f; the fixture's body starts on line 3. */
	static Seam seam(String body, int lo, int hi) {
		String source = "class A {\n void f(int[] xs) {\n" + body + " }\n void use(int x) {}\n int start() { return 1; }\n}\n";
		ParsedFile file = JavaFacts.parse("Fixture.java", source);
		assertTrue(file.parsed(), file.problem());
		MethodUnit m = file.methods().stream().filter(u -> u.name().equals("f")).findFirst().orElseThrow();
		return new MethodIndex(m.facts(), false).seam(lo, hi);
	}

	@Test
	void anAccumulatorDeclaredWithALiteralIsAnInputInsideTheLoop() {
		Seam s = seam("""
				  int count = 0;
				  for (int v : xs) {
				   count++;
				  }
				  use(count);
				""", 5, 5);
		assertEquals(List.of("count"), s.inputs());
		assertEquals(List.of("count"), s.outputs());
		assertEquals(List.of(), s.moved());
	}

	@Test
	void thePreviousIterationsValueIsAnInput() {
		Seam s = seam("""
				  int last = 0;
				  for (int v : xs) {
				   use(last);
				   last = v;
				  }
				""", 5, 5);
		assertEquals(List.of("last"), s.inputs());
		assertEquals(List.of(), s.outputs());
		assertEquals(List.of(), s.moved());
	}

	@Test
	void aRunOfTheWholeLoopStillMovesTheLiteralDeclaration() {
		// the run holds every touch inside the loop, so the literal can be declared again inside the piece
		Seam s = seam("""
				  int count = 0;
				  for (int v : xs) {
				   count++;
				  }
				""", 4, 6);
		assertEquals(List.of("xs"), s.inputs());
		assertEquals(List.of(), s.outputs());
		assertEquals(List.of("count"), s.moved());
	}

	@Test
	void aLiteralWithNoLoopStillMoves() {
		Seam s = seam("""
				  int c = 0;
				  c++;
				  use(c);
				""", 4, 4);
		assertEquals(List.of(), s.inputs());
		assertEquals(List.of("c"), s.outputs());
	}

	@Test
	void aComputedInitialValueWasAlreadyAnInput() {
		Seam s = seam("""
				  int count = start();
				  for (int v : xs) {
				   count++;
				  }
				  use(count);
				""", 5, 5);
		assertEquals(List.of("count"), s.inputs());
		assertEquals(List.of("count"), s.outputs());
	}

	@Test
	void aLocalNotWrittenInTheLoopStillMoves() {
		// read only: every iteration sees the literal, so it can be declared again inside the piece
		Seam s = seam("""
				  int k = 3;
				  for (int v : xs) {
				   use(k);
				  }
				""", 5, 5);
		assertEquals(List.of(), s.inputs());
		assertEquals(List.of("k"), s.moved());
	}
}
