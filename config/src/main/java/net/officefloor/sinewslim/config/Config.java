package net.officefloor.sinewslim.config;

import java.util.List;

import net.officefloor.sinewslim.core.Assessor;
import net.officefloor.sinewslim.core.Params;

/**
 * The effective configuration: {@code .sinew-slim.yml} over the defaults, with any overrides from
 * the command line on top (DESIGN.md, Configuration). One configuration drives every surface, so
 * the IDE, the hook and CI never disagree about a method.
 *
 * @param profile                 the profile the values started from: {@code default} or {@code study}
 * @param params                  the limits and weights of the search
 * @param records                 when nothing is feasible with one return, try records
 * @param structural              also solve with whole if, loop, try and switch statements only
 * @param preferStructuralWithin  show the structural proposal when it costs at most this much more
 * @param parameteriseFieldReads  offer a piece that only reads fields as static, the fields passed in
 * @param include                 the files scanned, as globs relative to the repository root
 * @param exclude                 the files left out, as globs
 * @param enforcement             what the review gate does with a finding
 * @param ratchet                 the review gates only on growth
 * @param warnings                what was ignored when the configuration was read, and why, for
 *                                every surface to show
 */
public record Config(String profile, Params params, boolean records, boolean structural, int preferStructuralWithin,
		boolean parameteriseFieldReads, List<String> include, List<String> exclude, Enforcement enforcement,
		boolean ratchet, List<String> warnings) {

	public enum Enforcement {
		OFF, WARN, BLOCK
	}

	public Config {
		include = List.copyOf(include);
		exclude = List.copyOf(exclude);
		warnings = List.copyOf(warnings);
	}

	/** The assessor every method is read with. */
	public Assessor assessor() {
		return new Assessor(params, records, structural, preferStructuralWithin);
	}
}
