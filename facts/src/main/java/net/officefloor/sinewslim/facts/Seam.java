package net.officefloor.sinewslim.facts;

import java.util.List;

/**
 * What crosses the boundary of a run of statements if it is extracted: the roles only. What the
 * crossing costs is the consumer's ({@code sinewslim-core} prices it as extracted methods).
 *
 * @param lo            the run's first line
 * @param hi            the run's last line
 * @param inputs        used inside and defined before, or carried round an enclosing loop that writes
 *                      them: the new method's parameters
 * @param outputs       written inside and read after, or read earlier in an enclosing loop: its
 *                      return value
 * @param moved         declared before with no value or a literal one, used only inside: the
 *                      declaration moves into the new method
 * @param fieldsRead    fields read inside and not written
 * @param fieldsWritten fields written inside
 * @param ownCalls      calls inside to the class's own instance methods
 * @param passed        the method's own parameters handed on unchanged; in {@code inputs} too
 *                      unless they are free (the framework reading)
 */
public record Seam(int lo, int hi, List<String> inputs, List<String> outputs, List<String> moved,
		List<String> fieldsRead, List<String> fieldsWritten, int ownCalls, List<String> passed) {

	public Seam {
		inputs = List.copyOf(inputs);
		outputs = List.copyOf(outputs);
		moved = List.copyOf(moved);
		fieldsRead = List.copyOf(fieldsRead);
		fieldsWritten = List.copyOf(fieldsWritten);
		passed = List.copyOf(passed);
	}

}
