package net.officefloor.sinewslim.cli.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.officefloor.sinewslim.core.Binding;
import net.officefloor.sinewslim.core.SlimResult;
import net.officefloor.sinewslim.facts.Seam;

/** One row per method, every method, for a spreadsheet; the cuts in one column as {@code lo-hi:inputs/outputs/binding}. */
final class CsvReport implements ReportWriter {

	static final List<String> HEADER = List.of("path", "type", "signature", "line", "end_line", "length", "headroom",
			"status", "obstacle", "slim_cost", "total_cost", "interface", "pieces", "remainder", "locked",
			"bound", "free", "remainder_locked", "cuts");

	@Override
	public void write(ScanReport r, int near, Appendable out) throws IOException {
		row(out, HEADER);
		for (MethodReport m : r.methods()) {
			SlimResult p = m.proposal();
			boolean shown = p.applicable() && p.feasible();
			List<String> cuts = new ArrayList<>();
			for (Seam s : p.cuts()) {
				cuts.add(s.lo() + "-" + s.hi() + ":" + s.inputs().size() + "/" + s.outputs().size() + "/"
						+ p.params().binding(s).name().toLowerCase(Locale.ROOT));
			}
			row(out, List.of(m.path(), m.type(), m.signature(), str(m.nameLine()), str(m.endLine()), str(m.length()),
					str(m.headroom()), m.status().name().toLowerCase(Locale.ROOT),
					m.assessment().obstacle() == null ? "" : m.assessment().obstacle().name().toLowerCase(Locale.ROOT),
					shown ? str(p.width()) : "", shown ? str(p.cost()) : "",
					shown ? str(p.interfaceSize()) : "", shown ? str(p.pieces()) : "", shown ? str(p.remainder()) : "",
					shown ? str(p.count(Binding.LOCKED)) : "", shown ? str(p.count(Binding.BOUND)) : "",
					shown ? str(p.count(Binding.FREE)) : "", shown ? String.valueOf(p.remainderLocked()) : "",
					String.join(";", cuts)));
		}
	}

	static String str(int i) {
		return String.valueOf(i);
	}

	static void row(Appendable out, List<String> cells) throws IOException {
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			String c = cells.get(i);
			if (c.indexOf(',') >= 0 || c.indexOf('"') >= 0 || c.indexOf('\n') >= 0) {
				out.append('"').append(c.replace("\"", "\"\"")).append('"');
			} else {
				out.append(c);
			}
		}
		out.append("\r\n");
	}
}
