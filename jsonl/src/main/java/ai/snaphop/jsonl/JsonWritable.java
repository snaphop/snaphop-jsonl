package ai.snaphop.jsonl;

/// A value that writes itself as JSON, for [JsonWriter#value(Object)] and
/// [Json#write(Object)], so a tree can hold more than maps, lists and scalars without
/// reflection.
@FunctionalInterface
public interface JsonWritable {

	/// Writes exactly one JSON value.
	/// @param w the writer.
	void writeJson(JsonWriter w);

}
