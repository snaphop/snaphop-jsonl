package ai.snaphop.jsonl;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/// JSON as plain Java values: maps (in order, with string keys), lists, strings,
/// `Long`s (`BigInteger`s past a `long`), `Double`s, booleans and nulls.
///
/// For JSON lines, or to read without building a tree, use [JsonReader] and
/// [JsonWriter].
public final class Json {

	private Json() {
	}

	/// Reads one JSON value.
	/// @param json the JSON.
	/// @return the value.
	/// @throws JsonException if it's not exactly one valid JSON value.
	public static @Nullable Object read(String json) {
		return readOne(JsonReader.of(json));
	}

	/// Reads one JSON value.
	/// @param json UTF-8 JSON.
	/// @return the value.
	/// @throws JsonException if it's not exactly one valid JSON value.
	public static @Nullable Object read(byte[] json) {
		return readOne(JsonReader.of(json));
	}

	/// Reads one JSON value from a stream, which is read to its end but not closed.
	/// @param json UTF-8 JSON.
	/// @return the value.
	/// @throws JsonException if it's not exactly one valid JSON value.
	public static @Nullable Object read(InputStream json) {
		return readOne(JsonReader.of(json));
	}

	private static @Nullable Object readOne(JsonReader r) {
		Object value = r.readValue();
		if (!r.atEnd()) {
			throw new JsonException("Expected the end of the input after a JSON value at " + r.location());
		}
		return value;
	}

	/// Writes a value as JSON.
	/// @param value as [JsonWriter#value(Object)] takes.
	/// @return the JSON.
	public static String write(@Nullable Object value) {
		return JsonWriter.ofBytes().value(value).toString();
	}

	/// Writes a value as UTF-8 JSON.
	/// @param value as [JsonWriter#value(Object)] takes.
	/// @return the JSON.
	public static byte[] writeBytes(@Nullable Object value) {
		return JsonWriter.ofBytes().value(value).toBytes();
	}

	/// Writes a value as UTF-8 JSON to a stream, which is flushed but not closed.
	/// @param value as [JsonWriter#value(Object)] takes.
	/// @param out where the JSON goes.
	public static void write(@Nullable Object value, OutputStream out) {
		JsonWriter.of(out).value(value).flush();
	}

	/// A value as an object.
	/// @param value a value [#read(String)] returned.
	/// @return it, if it's a map; otherwise an empty map.
	@SuppressWarnings("unchecked")
	public static Map<String, @Nullable Object> map(@Nullable Object value) {
		return value instanceof Map<?, ?> m ? (Map<String, @Nullable Object>) m : Map.of();
	}

	/// A value as an array.
	/// @param value a value [#read(String)] returned.
	/// @return it, if it's a list; otherwise an empty list.
	@SuppressWarnings("unchecked")
	public static List<@Nullable Object> list(@Nullable Object value) {
		return value instanceof List<?> l ? (List<@Nullable Object>) l : List.of();
	}

}
