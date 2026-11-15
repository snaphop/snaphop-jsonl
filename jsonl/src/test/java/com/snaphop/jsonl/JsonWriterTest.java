package com.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JsonWriterTest {

	@Test
	void writesTrees() {
		var tree = new LinkedHashMap<String, Object>();
		tree.put("s", "a\"b\\c\n\t\u0001é安😀");
		tree.put("n", null);
		tree.put("l", List.of(1L, -2, 3.5, true, false));
		tree.put("o", Map.of());
		tree.put("big", new BigDecimal("1.50"));
		tree.put("set", Set.of("x"));
		tree.put("array", new Object[] { 1L, "two" });
		tree.put("custom", (JsonWritable) w -> w.beginArray().value(Long.MIN_VALUE).endArray());
		assertEquals("{\"s\":\"a\\\"b\\\\c\\n\\t\\u0001é安😀\",\"n\":null,\"l\":[1,-2,3.5,true,false],\"o\":{},"
				+ "\"big\":1.50,\"set\":[\"x\"],\"array\":[1,\"two\"],\"custom\":[-9223372036854775808]}", Json.write(tree));
	}

	@Test
	void roundTripsReaderDocuments() {
		for (String doc : JsonReaderTest.DOCUMENTS) {
			Object tree = Json.read(doc);
			assertEquals(tree, Json.read(Json.write(tree)));
			var out = new ByteArrayOutputStream();
			try (var w = JsonWriter.of(out, 16)) {
				w.value(tree);
			}
			assertArrayEquals(Json.writeBytes(tree), out.toByteArray());
		}
	}

	/// Strings past the short path's length, with escapes and non-ASCII at the edges.
	@Test
	void writesLongStrings() {
		var random = new Random(5);
		String alphabet = "ab\"\\\n\u0000é安😀";
		for (int i = 0; i < 2000; i++) {
			var sb = new StringBuilder();
			int n = random.nextInt(300);
			while (sb.length() < n) {
				int k = random.nextInt(alphabet.length() - 1);
				sb.append(alphabet.charAt(k) == '\uD83D' ? "😀" : String.valueOf(alphabet.charAt(k)));
			}
			String s = sb.toString();
			assertEquals(s, Json.read(Json.write(s)));
			assertEquals(s, Json.read(Json.write(List.of(s))) instanceof List<?> l ? l.getFirst() : null);
		}
	}

	/// avaje-json 3.16 wrote each `char` of a raw value as one byte.
	@Test
	void writesRawUtf8() {
		var w = JsonWriter.ofBytes();
		w.beginObject().name("ok").value("安").name("raw").rawValue("{\"x\":\"安😀\"}");
		w.name("long").rawValue("[\"" + "安".repeat(100) + "\"]").endObject();
		assertEquals("{\"ok\":\"安\",\"raw\":{\"x\":\"安😀\"},\"long\":[\"" + "安".repeat(100) + "\"]}", w.toString());
	}

	@Test
	void writesLoneSurrogatesAsReplacementCharacters() {
		assertEquals("\"a\uFFFDb\uFFFD\"", Json.write("a\uD800b\uDC00"));
		assertEquals("\"" + "x".repeat(70) + "\uFFFD?\"", Json.write("x".repeat(70) + "\uD800?"));
		assertEquals("[\"\uFFFD\uD83D\uDE00\"]", JsonWriter.ofBytes().rawValue("[\"\uDE00\uD83D\uDE00\"]").toString());
		assertEquals("\"" + "安".repeat(70) + "\uFFFD\"", Json.write("安".repeat(70) + "\uDBFF"));
	}

	@Test
	void writesJsonLines() {
		var w = JsonWriter.ofBytes();
		w.beginObject().name("a").value(1).endObject();
		w.value("two");
		w.beginArray().endArray();
		assertEquals("{\"a\":1}\n\"two\"\n[]", w.toString());
	}

	@Test
	void indents() {
		var w = JsonWriter.ofBytes().indent("  ");
		w.value(Json.read("{\"a\":[1,{\"b\":null}],\"c\":{},\"d\":[]}"));
		assertEquals("""
				{
				  "a": [
				    1,
				    {
				      "b": null
				    }
				  ],
				  "c": {},
				  "d": []
				}""", w.toString());
	}

	@Test
	void writesNumbers() {
		long[] longs = { 0, 1, -1, 9, 10, 99, 100, 12345, Long.MAX_VALUE, Long.MIN_VALUE, Integer.MIN_VALUE };
		var w = JsonWriter.ofBytes();
		w.beginArray();
		for (long l : longs) {
			w.value(l);
		}
		w.value(0.1).value(-0.0).value(1e300).endArray();
		assertEquals(Arrays.stream(longs).mapToObj(Long::toString).toList().toString().replace(" ", "")
				.replace("]", ",0.1,-0.0,1.0E300]"), w.toString());
		assertThrows(JsonException.class, () -> JsonWriter.ofBytes().value(Double.NaN));
	}

	@Test
	void checksNames() {
		assertThrows(JsonException.class, () -> JsonWriter.ofBytes().beginArray().name("a"));
		assertThrows(JsonException.class, () -> JsonWriter.ofBytes().beginObject().value(1));
		assertThrows(JsonException.class, () -> JsonWriter.ofBytes().beginObject().name("a").endObject());
		assertThrows(JsonException.class, () -> Json.write(new Object()));
		var cyclic = new java.util.ArrayList<Object>();
		cyclic.add(cyclic);
		assertThrows(JsonException.class, () -> Json.write(cyclic));
	}

	@Test
	void writesBytesAsUtf8() {
		assertArrayEquals("\"é\"".getBytes(StandardCharsets.UTF_8), Json.writeBytes("é"));
	}

}
