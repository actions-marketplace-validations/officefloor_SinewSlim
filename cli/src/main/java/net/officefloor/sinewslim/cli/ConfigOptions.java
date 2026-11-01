package net.officefloor.sinewslim.cli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.officefloor.sinewslim.config.Config;
import net.officefloor.sinewslim.config.ConfigLoader;
import picocli.CommandLine.Option;

/** The options every command takes to find the repository and read its configuration; flags override the file. */
final class ConfigOptions {

	@Option(names = "--repo", description = "The repository root (default: the current directory).", defaultValue = ".")
	Path repo;

	@Option(names = "--config", description = "The configuration file (default: .sinew-slim.yml at the root, if there).")
	Path config;

	@Option(names = "--profile", description = "The values to start from: default or study.")
	String profile;

	@Option(names = "--trigger", description = "A method with this many code lines or more is over the cap.")
	Integer trigger;

	@Option(names = "--target", description = "Every piece and the remainder must end at this many lines or fewer.")
	Integer target;

	@Option(names = "--min-piece", description = "The smallest run worth extracting, in code lines.")
	Integer minPiece;

	@Option(names = "--max-inputs", description = "The most parameters a piece may need.")
	Integer maxInputs;

	@Option(names = "--soft-inputs", description = "Parameters beyond this cost the premium each.")
	Integer softInputs;

	@Option(names = "--input-premium", description = "The extra cost per parameter beyond --soft-inputs.")
	Integer inputPremium;

	@Option(names = "--set", description = "Any configuration key, as in the file: --set framework.annotations=Next.", paramLabel = "KEY=VALUE")
	Map<String, String> set = new LinkedHashMap<>();

	Path root() {
		return repo.toAbsolutePath().normalize();
	}

	Map<String, Object> overrides() {
		Map<String, Object> o = new LinkedHashMap<>(set);
		put(o, "profile", profile);
		put(o, "trigger", trigger);
		put(o, "target", target);
		put(o, "min_piece", minPiece);
		put(o, "max_inputs", maxInputs);
		put(o, "soft_inputs", softInputs);
		put(o, "input_premium", inputPremium);
		return o;
	}

	private static void put(Map<String, Object> o, String key, Object value) {
		if (value != null) {
			o.put(key, value);
		}
	}

	/** The configuration, each warning from reading it printed to standard error. */
	Config load() {
		Config c = ConfigLoader.load(root(), config, overrides());
		c.warnings().forEach(w -> System.err.println("warning: " + w));
		return c;
	}
}
