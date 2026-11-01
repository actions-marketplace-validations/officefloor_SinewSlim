package net.officefloor.sinewslim.facts;

/**
 * The key to one kind of fact a consumer adds beside the shared schema ({@link MethodFacts}):
 * an annotation reading, the try/catch structure, the declared exceptions. Keys compare by
 * identity, so two consumers that pick the same name do not collide; a consumer keeps its keys
 * as constants and versions its own facts. Values are immutable.
 *
 * @param <T> the type of the fact
 */
public final class FactKey<T> {

	private final String name;

	private FactKey(String name) {
		this.name = name;
	}

	/** A new key; the name is for reading only, {@code myproject.annotations} for example. */
	public static <T> FactKey<T> of(String name) {
		return new FactKey<>(name);
	}

	public String name() {
		return name;
	}

	@Override
	public String toString() {
		return name;
	}
}
