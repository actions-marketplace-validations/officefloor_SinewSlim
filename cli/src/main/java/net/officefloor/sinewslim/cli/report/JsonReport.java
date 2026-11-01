package net.officefloor.sinewslim.cli.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import net.officefloor.sinewslim.core.Pieces;
import net.officefloor.sinewslim.config.ConfigLoader;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.SlimResult;

/**
 * The contract: every method, every piece and the parameters it was read under, in a versioned
 * schema ({@link ScanReport#SCHEMA}). The other formats and later tools read this.
 */
public final class JsonReport implements ReportWriter {

	public static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	@Override
	public void write(ScanReport r, int near, Appendable out) throws IOException {
		out.append(JSON.writeValueAsString(model(r))).append(System.lineSeparator());
	}

	static Map<String, Object> model(ScanReport r) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("tool", "sinew-slim");
		doc.put("version", r.version());
		doc.put("schema", ScanReport.SCHEMA);
		doc.put("root", r.root());
		doc.put("seconds", Math.round(r.seconds() * 10) / 10.0);
		doc.put("config", ConfigLoader.values(r.config()));
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("files", r.files());
		summary.put("unparsed", r.unparsed().size());
		summary.put("methods", r.methods().size());
		for (Status s : Status.values()) {
			summary.put(s.name().toLowerCase(Locale.ROOT), r.count(s));
		}
		summary.put("over_cap", r.overCap());
		summary.put("excess_lines", r.excess());
		Map<String, Integer> dist = new LinkedHashMap<>();
		r.slimCostDistribution().forEach((k, v) -> dist.put(String.valueOf(k), v));
		summary.put("slim_cost_distribution", dist);
		doc.put("summary", summary);
		List<Object> unparsed = new ArrayList<>();
		for (ScanReport.Unparsed u : r.unparsed()) {
			unparsed.add(Map.of("path", u.path(), "problem", u.problem()));
		}
		doc.put("unparsed", unparsed);
		List<Object> methods = new ArrayList<>();
		for (MethodReport m : r.methods()) {
			methods.add(method(m, r.config().parameteriseFieldReads()));
		}
		doc.put("methods", methods);
		List<Object> classes = new ArrayList<>();
		for (ClassReport c : r.classes()) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("path", c.path());
			o.put("type", c.type());
			o.put("over_cap", c.overCap());
			o.put("pieces", c.pieces());
			o.put("locked_pieces", c.lockedPieces());
			o.put("remainders", c.remainders());
			o.put("locked_remainders", c.lockedRemainders());
			o.put("locked_share", Math.round(c.lockedShare() * 1000) / 1000.0);
			o.put("not_slimmable", c.notSlimmable());
			classes.add(o);
		}
		doc.put("classes", classes);
		return doc;
	}

	public static Map<String, Object> method(MethodReport m, boolean parameteriseFieldReads) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("path", m.path());
		o.put("type", m.type());
		o.put("signature", m.signature());
		o.put("line", m.nameLine());
		o.put("end_line", m.endLine());
		o.put("length", m.length());
		o.put("headroom", m.headroom());
		o.put("status", m.status().name().toLowerCase(Locale.ROOT));
		if (m.status() == Status.UNDER_CAP) {
			return o;
		}
		if (m.assessment().obstacle() != null) {
			o.put("obstacle", m.assessment().obstacle().name().toLowerCase(Locale.ROOT));
			o.put("reason", m.assessment().reason());
		}
		SlimResult p = m.proposal();
		o.put("feasible", p.feasible());
		if (p.feasible()) {
			o.put("slim_cost", p.width());
			o.put("total_cost", p.cost());
			o.put("interface", p.interfaceSize());
			o.put("remainder", p.remainder());
			o.put("remainder_locked", p.remainderLocked());
			o.put("structural", p.params().structuralOnly());
			List<Object> pieces = new ArrayList<>();
			for (Pieces.Piece d : Describe.pieces(p, parameteriseFieldReads)) {
				Map<String, Object> x = new LinkedHashMap<>();
				x.put("lines", List.of(d.lo(), d.hi()));
				x.put("length", d.lines());
				x.put("saves", d.saves());
				x.put("inputs", d.inputs());
				x.put("passed", d.passed());
				x.put("outputs", d.outputs());
				x.put("declares", d.moved());
				x.put("binding", d.binding());
				pieces.add(x);
			}
			o.put("pieces", pieces);
		}
		SlimResult s = m.assessment().structural();
		if (s != null) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("feasible", s.feasible());
			if (s.feasible()) {
				x.put("slim_cost", s.width());
				x.put("total_cost", s.cost());
				x.put("pieces", s.pieces());
			}
			o.put("structural_variant", x);
		}
		return o;
	}
}
