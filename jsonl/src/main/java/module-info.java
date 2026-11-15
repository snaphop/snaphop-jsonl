/// Small, fast JSON and JSON lines: a streaming [com.snaphop.jsonl.JsonReader] and
/// [com.snaphop.jsonl.JsonWriter], and [com.snaphop.jsonl.Json] for JSON as maps,
/// lists, strings, numbers, booleans and nulls.
///
/// There is no binding, no reflection and no `Unsafe`, and no limit on the size of a
/// string or a document beyond memory.
module com.snaphop.jsonl {
	exports com.snaphop.jsonl;

	requires static transitive org.jspecify;
}
