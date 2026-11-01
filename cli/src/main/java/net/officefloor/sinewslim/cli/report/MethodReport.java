package net.officefloor.sinewslim.cli.report;

import net.officefloor.sinewslim.core.Assessment;
import net.officefloor.sinewslim.core.Assessment.Status;
import net.officefloor.sinewslim.core.SlimResult;

/**
 * One method of the scan and what SinewSlim says about it.
 *
 * @param path       the file, relative to the repository root
 * @param type       the declaring type
 * @param signature  the name and parameter types
 * @param nameLine   the line of its name
 * @param endLine    its last line
 * @param length     its code lines
 * @param assessment the verdict and the proposal
 */
public record MethodReport(String path, String type, String signature, int nameLine, int endLine, int length,
		Assessment assessment) {

	public Status status() {
		return assessment.status();
	}

	public SlimResult proposal() {
		return assessment.proposal();
	}

	/** Lines left before the method reaches the trigger; zero or less once it is long. */
	public int headroom() {
		return proposal().params().trigger() - length;
	}

	/** {@code Type.signature}, or the signature alone at the top level. */
	public String qualified() {
		return type.isEmpty() ? signature : type + "." + signature;
	}

	/** {@code path:line}, the form editors and terminals turn into a link. */
	public String location() {
		return path + ":" + nameLine;
	}
}
