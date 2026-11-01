package net.officefloor.sinewslim.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import net.officefloor.sinewslim.config.Config.Enforcement;
import net.officefloor.sinewslim.core.Params;

/**
 * Reads {@code .sinew-slim.yml}. Every key has a default and an empty file is valid; an unknown
 * key, a value of the wrong type or a limit out of range is an error naming the key, so a typo
 * never silently falls back to a default.
 */
public final class ConfigLoader {

	/** The file every surface looks for at the repository root. */
	public static final String FILE = ".sinew-slim.yml";

	private ConfigLoader() {
	}

	/** A configuration that could not be read: the message names the key and what was wrong. */
	public static final class ConfigException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ConfigException(String message) {
			super(message);
		}
	}

	/** The values a profile starts from, in the order the dump writes them. */
	static Map<String, Object> profile(String name) {
		Params p = switch (name) {
		case "default" -> Params.DEFAULTS;
		case "study" -> Params.STUDY;
		default -> throw new ConfigException("profile: unknown profile '" + name + "' (default or study)");
		};
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("profile", name);
		v.put("trigger", p.trigger());
		v.put("target", p.target());
		v.put("min_piece", p.minPiece());
		v.put("min_saving", p.minSaving());
		v.put("max_outputs", p.maxOutputs());
		v.put("trailing_return", p.trailingReturn());
		v.put("max_inputs", p.maxInputs());
		v.put("soft_inputs", p.softInputs() < 0 ? null : p.softInputs());
		v.put("input_premium", p.inputPremium());
		v.put("output_weight", p.outputWeight());
		v.put("multi_output_weight", p.multiOutputWeight());
		v.put("count_unpacking", p.countUnpacking());
		v.put("piece_cost", p.pieceCost());
		v.put("records", true);
		v.put("structural", true);
		v.put("prefer_structural_within", 1);
		v.put("lock_on_any_field", p.lockOnAnyField());
		v.put("parameterise_field_reads", true);
		v.put("include", new ArrayList<>(List.of("**/*.java")));
		v.put("exclude", new ArrayList<>(List.of("**/generated/**", "**/target/**", "**/build/**")));
		v.put("enforcement", "warn");
		v.put("ratchet", true);
		return v;
	}

	/** The defaults. */
	public static Config defaults() {
		return build(profile("default"), List.of());
	}

	/**
	 * The configuration for a repository: its {@code .sinew-slim.yml} (or {@code file} when given)
	 * over the defaults, then {@code overrides}, keys as in the file ({@code min_piece},
	 * {@code max_inputs}). A {@code profile} in either picks the starting values.
	 */
	public static Config load(Path root, Path file, Map<String, Object> overrides) {
		Path f = file != null ? file : root.resolve(FILE);
		Map<String, Object> fromFile = Map.of();
		if (file != null || Files.isRegularFile(f)) {
			fromFile = read(f);
		}
		return resolve(fromFile, overrides);
	}

	/** What a {@code framework} key now says, wherever it is given: it is ignored. */
	static final String FRAMEWORK_REMOVED = "framework: removed in SinewSlim 0.2 and ignored; every method is read one way,"
			+ " as extracted methods";

	/** The file's values and the overrides over the profile they name. */
	public static Config resolve(Map<String, Object> fromFile, Map<String, Object> overrides) {
		Object profile = overrides.containsKey("profile") ? overrides.get("profile") : fromFile.get("profile");
		Map<String, Object> values = profile(profile == null ? "default" : String.valueOf(profile));
		List<String> warnings = new ArrayList<>();
		merge(values, withoutRemoved(fromFile, warnings), "");
		merge(values, withoutRemoved(nest(overrides), warnings), "");
		return build(values, warnings);
	}

	/** The values less the keys SinewSlim no longer has, each noted once in the warnings. */
	static Map<String, Object> withoutRemoved(Map<String, Object> values, List<String> warnings) {
		if (!values.containsKey("framework")) {
			return values;
		}
		Map<String, Object> out = new LinkedHashMap<>(values);
		out.remove("framework");
		if (!warnings.contains(FRAMEWORK_REMOVED)) {
			warnings.add(FRAMEWORK_REMOVED);
		}
		return out;
	}

	/** The YAML of a file, as a map. */
	static Map<String, Object> read(Path f) {
		String text;
		try {
			text = Files.readString(f, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ConfigException(f + ": cannot read (" + e.getMessage() + ")");
		}
		Object doc;
		try {
			doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
		} catch (RuntimeException e) {
			throw new ConfigException(f + ": not valid YAML (" + e.getMessage().lines().findFirst().orElse("") + ")");
		}
		if (doc == null) {
			return Map.of();
		}
		if (!(doc instanceof Map<?, ?> m)) {
			throw new ConfigException(f + ": expected a mapping of keys to values");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		m.forEach((k, v) -> out.put(String.valueOf(k), v));
		return out;
	}

	/** {@code a.b: v} keys as nested maps, so a command-line override reads as the file would. */
	static Map<String, Object> nest(Map<String, Object> flat) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : flat.entrySet()) {
			String[] parts = e.getKey().split("\\.");
			Map<String, Object> at = out;
			for (int i = 0; i < parts.length - 1; i++) {
				@SuppressWarnings("unchecked")
				Map<String, Object> next = (Map<String, Object>) at.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
				at = next;
			}
			at.put(parts[parts.length - 1], e.getValue());
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	static void merge(Map<String, Object> into, Map<String, Object> from, String prefix) {
		for (Map.Entry<String, Object> e : from.entrySet()) {
			String key = e.getKey();
			if (!into.containsKey(key)) {
				throw new ConfigException(prefix + key + ": unknown key (known: " + String.join(", ", into.keySet()) + ")");
			}
			Object cur = into.get(key);
			if (cur instanceof Map<?, ?> curMap) {
				if (!(e.getValue() instanceof Map<?, ?> m)) {
					throw new ConfigException(prefix + key + ": expected a mapping");
				}
				Map<String, Object> sub = new LinkedHashMap<>();
				m.forEach((k, v) -> sub.put(String.valueOf(k), v));
				merge((Map<String, Object>) curMap, sub, prefix + key + ".");
			} else {
				into.put(key, e.getValue());
			}
		}
	}

	@SuppressWarnings("unchecked")
	static Config build(Map<String, Object> v, List<String> warnings) {
		Params params = Params.DEFAULTS.toBuilder() //
				.trigger(integer(v, "trigger", 2)) //
				.target(integer(v, "target", 1)) //
				.minPiece(integer(v, "min_piece", 1)) //
				.minSaving(integer(v, "min_saving", 0)) //
				.maxOutputs(limit(v, "max_outputs")) //
				.trailingReturn(bool(v, "trailing_return")) //
				.maxInputs(limit(v, "max_inputs")) //
				.softInputs(v.get("soft_inputs") == null ? -1 : integer(v, "soft_inputs", 0)) //
				.inputPremium(integer(v, "input_premium", 0)) //
				.outputWeight(integer(v, "output_weight", 0)) //
				.multiOutputWeight(integer(v, "multi_output_weight", 0)) //
				.countUnpacking(bool(v, "count_unpacking")) //
				.pieceCost(integer(v, "piece_cost", 0)) //
				.lockOnAnyField(bool(v, "lock_on_any_field")) //
				.build();
		if (params.target() >= params.trigger()) {
			throw new ConfigException("target: must be less than trigger (" + params.target() + " >= " + params.trigger() + ")");
		}
		if (params.minPiece() > params.target()) {
			throw new ConfigException("min_piece: must be at most target (" + params.minPiece() + " > " + params.target() + ")");
		}
		if (params.softInputs() >= 0 && params.maxInputs() >= 0 && params.softInputs() > params.maxInputs()) {
			throw new ConfigException("soft_inputs: must be at most max_inputs (" + params.softInputs() + " > "
					+ params.maxInputs() + ")");
		}
		String enforcement = string(v, "enforcement").toUpperCase(Locale.ROOT);
		Enforcement e;
		try {
			e = Enforcement.valueOf(enforcement);
		} catch (IllegalArgumentException x) {
			throw new ConfigException("enforcement: expected off, warn or block, not '" + v.get("enforcement") + "'");
		}
		return new Config(string(v, "profile"), params, bool(v, "records"), bool(v, "structural"),
				v.get("prefer_structural_within") == null ? -1 : integer(v, "prefer_structural_within", 0),
				bool(v, "parameterise_field_reads"), strings(v, "include"), strings(v, "exclude"), e, bool(v, "ratchet"),
				warnings);
	}

	private static int integer(Map<String, Object> v, String key, int min) {
		Object o = v.get(key);
		int i;
		if (o instanceof Number n && n.doubleValue() == n.intValue()) {
			i = n.intValue();
		} else if (o instanceof String s && s.strip().matches("-?\\d+")) {
			i = Integer.parseInt(s.strip());
		} else {
			throw new ConfigException(key + ": expected a whole number, not '" + o + "'");
		}
		if (i < min) {
			throw new ConfigException(key + ": must be at least " + min + ", not " + i);
		}
		return i;
	}

	/** A limit: a number of zero or more, or -1 or null for none. */
	private static int limit(Map<String, Object> v, String key) {
		return v.get(key) == null ? -1 : integer(v, key, -1);
	}

	private static boolean bool(Map<String, Object> v, String key) {
		return bool(v, key, key);
	}

	private static boolean bool(Map<String, Object> v, String key, String name) {
		Object o = v.get(key);
		if (o instanceof Boolean b) {
			return b;
		}
		if (o instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
			return Boolean.parseBoolean(s);
		}
		throw new ConfigException(name + ": expected true or false, not '" + o + "'");
	}

	private static String string(Map<String, Object> v, String key) {
		Object o = v.get(key);
		if (o == null) {
			throw new ConfigException(key + ": expected a value");
		}
		return String.valueOf(o);
	}

	private static List<String> strings(Map<String, Object> v, String key) {
		Object o = v.get(key.substring(key.lastIndexOf('.') + 1));
		if (o instanceof List<?> l) {
			List<String> out = new ArrayList<>();
			for (Object x : l) {
				out.add(String.valueOf(x));
			}
			return out;
		}
		if (o instanceof String s) {
			List<String> out = new ArrayList<>();
			for (String x : s.split(",")) {
				if (!x.isBlank()) {
					out.add(x.strip());
				}
			}
			return out;
		}
		throw new ConfigException(key + ": expected a list, not '" + o + "'");
	}

	/** The effective configuration as YAML, every key written: what {@code sinew-slim config} prints. */
	public static String dump(Config c) {
		DumperOptions o = new DumperOptions();
		o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		o.setIndent(2);
		o.setIndicatorIndent(0);
		return new Yaml(o).dump(values(c));
	}

	/** The effective configuration, keys as in the file, every key present: what a report records it was read under. */
	public static Map<String, Object> values(Config c) {
		Params p = c.params();
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("profile", c.profile());
		v.put("trigger", p.trigger());
		v.put("target", p.target());
		v.put("min_piece", p.minPiece());
		v.put("min_saving", p.minSaving());
		v.put("max_outputs", p.maxOutputs() < 0 ? null : p.maxOutputs());
		v.put("trailing_return", p.trailingReturn());
		v.put("max_inputs", p.maxInputs() < 0 ? null : p.maxInputs());
		v.put("soft_inputs", p.softInputs() < 0 ? null : p.softInputs());
		v.put("input_premium", p.inputPremium());
		v.put("output_weight", p.outputWeight());
		v.put("multi_output_weight", p.multiOutputWeight());
		v.put("count_unpacking", p.countUnpacking());
		v.put("piece_cost", p.pieceCost());
		v.put("records", c.records());
		v.put("structural", c.structural());
		v.put("prefer_structural_within", c.preferStructuralWithin() < 0 ? null : c.preferStructuralWithin());
		v.put("lock_on_any_field", p.lockOnAnyField());
		v.put("parameterise_field_reads", c.parameteriseFieldReads());
		v.put("include", c.include());
		v.put("exclude", c.exclude());
		v.put("enforcement", c.enforcement().name().toLowerCase(Locale.ROOT));
		v.put("ratchet", c.ratchet());
		return v;
	}
}
