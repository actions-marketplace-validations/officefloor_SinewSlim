package net.officefloor.sinewslim.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The scan end to end: a small repository on disk, the command run, every format read back. */
class ScanTest {

	@TempDir
	Path repo;

	@TempDir
	Path out;

	static final ObjectMapper JSON = new ObjectMapper();

	/** Thirty lines of steps, each handing one value to the next. */
	static String sequential() {
		StringBuilder b = new StringBuilder("package p;\nclass Orders {\n\tString place(String request, int quantity) {\n");
		b.append("\t\tint v0 = request.length() + quantity;\n");
		for (int i = 1; i <= 26; i++) {
			b.append("\t\tint v").append(i).append(" = v").append(i - 1).append(" * ").append(i + 1).append(";\n");
		}
		return b.append("\t\treturn request + v26;\n\t}\n}\n").toString();
	}

	/** Six accumulators updated in turn: only a head piece returning a record fits. */
	static String interwoven() {
		StringBuilder b = new StringBuilder("package p;\nclass Stats {\n\tdouble summary(double[] x) {\n");
		b.append("\t\tdouble a = x[24], b = x[25], c = x[26], d = x[27], e = x[28], f = x[29];\n");
		for (int i = 0; i < 24; i++) {
			b.append("\t\t").append((char) ('a' + i % 6)).append(" += x[").append(i).append("];\n");
		}
		return b.append("\t\treturn a + b + c + d + e + f;\n\t}\n}\n").toString();
	}

	/** Every statement returns early: no run can move. */
	static String guarded() {
		StringBuilder b = new StringBuilder("package p;\nclass Guards {\n\tint check(int x) {\n");
		for (int i = 0; i < 26; i++) {
			b.append("\t\tif (x == ").append(i).append(") return ").append(i * 2).append(";\n");
		}
		return b.append("\t\treturn -1;\n\t}\n}\n").toString();
	}

	@BeforeEach
	void write() throws IOException {
		Path src = Files.createDirectories(repo.resolve("src/p"));
		Files.writeString(src.resolve("Orders.java"), sequential());
		Files.writeString(src.resolve("Stats.java"), interwoven());
		Files.writeString(src.resolve("Guards.java"), guarded());
		Files.writeString(src.resolve("Small.java"), "package p;\nclass Small { int f(int x) { return x; } }\n");
		Files.writeString(src.resolve("Broken.java"), "package p;\nclass Broken { void f( { }\n");
		Path gen = Files.createDirectories(repo.resolve("target/generated"));
		Files.writeString(gen.resolve("Gen.java"), guarded().replace("Guards", "Gen"));
		Path hidden = Files.createDirectories(repo.resolve(".hidden"));
		Files.writeString(hidden.resolve("H.java"), guarded().replace("Guards", "H"));
	}

	JsonNode scan(String... extra) throws IOException {
		Path file = out.resolve("scan.json");
		List<String> args = new ArrayList<>(
				List.of("scan", "--repo", repo.toString(), "--format", "json", "--out", file.toString()));
		args.addAll(List.of(extra));
		assertEquals(SinewSlim.OK, SinewSlim.run(args.toArray(String[]::new)));
		return JSON.readTree(file.toFile());
	}

	static Map<String, JsonNode> byType(JsonNode doc) {
		Map<String, JsonNode> m = new HashMap<>();
		doc.get("methods").forEach(x -> m.put(x.get("type").asText(), x));
		return m;
	}

	@Test
	void everyMethodWithItsVerdict() throws IOException {
		JsonNode doc = scan();
		assertEquals(2, doc.get("schema").asInt()); // 2: no "reading" since the framework reading went
		JsonNode s = doc.get("summary");
		assertEquals(5, s.get("files").asInt(), "target/ excluded, the hidden directory left out");
		assertEquals(1, s.get("unparsed").asInt());
		assertEquals(4, s.get("methods").asInt());
		Map<String, JsonNode> m = byType(doc);
		assertEquals("slimmable", m.get("Orders").get("status").asText());
		assertTrue(m.get("Orders").get("pieces").size() >= 1);
		assertEquals("slimmable_with_record", m.get("Stats").get("status").asText());
		assertEquals("outputs", m.get("Stats").get("obstacle").asText());
		assertEquals("not_slimmable", m.get("Guards").get("status").asText());
		assertEquals("jumps", m.get("Guards").get("obstacle").asText());
		assertEquals("under_cap", m.get("Small").get("status").asText());
		assertEquals("src/p/Broken.java", doc.get("unparsed").get(0).get("path").asText());
	}

	@Test
	void flagsOverrideTheConfiguration() throws IOException {
		Files.writeString(repo.resolve(".sinew-slim.yml"), "trigger: 100\ntarget: 99\n");
		assertEquals("under_cap", byType(scan()).get("Orders").get("status").asText());
		assertEquals("slimmable", byType(scan("--trigger", "26", "--target", "25")).get("Orders").get("status").asText());
	}

	@Test
	void theRemovedFrameworkReadingIsIgnoredWithAWarning() throws IOException {
		Files.writeString(repo.resolve("src/p/Stats.java"), interwoven().replace("class Stats", "@Section class Stats"));
		PrintStream err = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		Map<String, JsonNode> m;
		try {
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			m = byType(scan("--set", "framework.annotations=Section"));
		} finally {
			System.setErr(err);
		}
		assertTrue(captured.toString(StandardCharsets.UTF_8).contains("warning: framework: removed"),
				captured.toString(StandardCharsets.UTF_8));
		// every method is read one way: no reading to report, and the annotation changes nothing
		assertFalse(m.get("Stats").has("reading"));
		assertEquals(byType(scan()).get("Stats").get("status"), m.get("Stats").get("status"));
	}

	@Test
	void everyFormatWrites() throws IOException {
		for (String f : new String[] { "text", "markdown", "html", "csv", "sarif" }) {
			Path file = out.resolve("scan." + f);
			assertEquals(SinewSlim.OK, SinewSlim.run("scan", "--repo", repo.toString(), "--format", f, "--out", file.toString()));
			String text = Files.readString(file);
			assertTrue(text.contains("Guards"), f + " names the method that cannot be slimmed");
		}
		assertTrue(Files.readString(out.resolve("scan.text")).contains("src/p/Guards.java:3: Guards.check(int)"));
		assertEquals(4 + 1, Files.readAllLines(out.resolve("scan.csv")).size());
		JsonNode sarif = JSON.readTree(out.resolve("scan.sarif").toFile());
		assertEquals("2.1.0", sarif.get("version").asText());
		assertEquals(3, sarif.get("runs").get(0).get("results").size());
		assertTrue(Files.readString(out.resolve("scan.html")).startsWith("<!doctype html>"));
	}

	@Test
	void mistakesExitWithOne() {
		assertEquals(SinewSlim.ERROR, SinewSlim.run("scan", "--repo", repo.toString(), "--format", "pdf"));
		assertEquals(SinewSlim.ERROR, SinewSlim.run("scan", "--repo", repo.toString(), "--set", "min_pice=3"));
		assertEquals(SinewSlim.ERROR, SinewSlim.run("scan", "--no-such-flag"));
	}

	@Test
	void baselineRecordsEveryMethod() throws IOException {
		assertEquals(SinewSlim.OK, SinewSlim.run("baseline", "--repo", repo.toString()));
		JsonNode b = JSON.readTree(repo.resolve(".sinew-slim-baseline.json").toFile());
		assertEquals(4, b.get("methods").size());
		assertEquals(26, b.get("trigger").asInt());
	}
}
