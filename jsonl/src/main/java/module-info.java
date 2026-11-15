/// Small, fast JSON and JSON lines: a streaming [ai.snaphop.jsonl.JsonReader] and
/// [ai.snaphop.jsonl.JsonWriter], and [ai.snaphop.jsonl.Json] for JSON as maps,
/// lists, strings, numbers, booleans and nulls.
///
/// There is no binding, no reflection and no `Unsafe`, and no limit on the size of a
/// string or a document beyond memory.
module ai.snaphop.jsonl {
	exports ai.snaphop.jsonl;

	requires static transitive org.jspecify;
}
