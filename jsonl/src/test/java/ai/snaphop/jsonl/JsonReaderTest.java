package ai.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JsonReaderTest {

	/// Documents that cover every token, escape and number form.
	static final List<String> DOCUMENTS = List.of("""
			{"a":[1,-2,3.5,-0.25e-3,1E+2,0,-0,9223372036854775807,-9223372036854775808,123456789012345678901234567890],
			 "s":"plain","e":"q\\"b\\\\s\\/n\\nt\\tr\\rb\\bf\\fu\\u00e9\\u5b89\\ud83d\\ude00!",
			 "u":"Zürich 安 😀","t":true,"f":false,"n":null,"o":{},"l":[],"deep":[[[{"x":[{}]}]]]}""",
			"  [ 1 , \"two\" , { \"three\" : 3 } , [ ] , { } ]  ", "\"top\"", "42", "-1.5", "true", "null",
			"{\"long\":\"" + "x".repeat(70_000) + "\",\"after\":1}",
			"{\"escaped\":\"" + "a\\n".repeat(30_000) + "\"}");

	@Test
	void readsTrees() {
		var tree = Json.map(Json.read(DOCUMENTS.getFirst()));
		assertEquals(List.of(1L, -2L, 3.5, -0.25e-3, 100.0, 0L, 0L, Long.MAX_VALUE, Long.MIN_VALUE,
				new BigInteger("123456789012345678901234567890")), tree.get("a"));
		assertEquals("q\"b\\s/n\nt\tr\rb\bf\fué安😀!", tree.get("e"));
		assertEquals("Zürich 安 😀", tree.get("u"));
		assertEquals(true, tree.get("t"));
		assertTrue(tree.containsKey("n"));
		assertEquals(null, tree.get("n"));
		assertEquals(List.of("a", "s", "e", "u", "t", "f", "n", "o", "l", "deep"), new ArrayList<>(tree.keySet()));
	}

	/// The same values whichever way the bytes arrive: whole, a byte at a time, or in
	/// random chunks through a tiny buffer.
	@Test
	void streamsAcrossEveryBufferEdge() {
		var random = new Random(1);
		for (String doc : DOCUMENTS) {
			byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
			Object expected = Json.read(bytes);
			assertEquals(expected, readTree(JsonReader.of(new Trickle(bytes, null), 16)), doc);
			for (int i = 0; i < 5; i++) {
				assertEquals(expected, readTree(JsonReader.of(new Trickle(bytes, random), 16 + random.nextInt(64))));
			}
			assertEquals(expected, Json.read(new ByteArrayInputStream(bytes)));
		}
	}

	/// Shifts a document across a small buffer so every token meets the buffer's end,
	/// reading it with the pull API, which is where avaje's bug was.
	@Test
	void pullsAcrossEveryBufferEdge() {
		String doc = "{\"a\":[\"" + "x".repeat(40) + "\",\"y\",12345,true,null,{\"k\":\"v\\u00e9\"}],\"b\":-1.5e3}";
		Object expected = Json.read(doc);
		for (int pad = 0; pad < 80; pad++) {
			byte[] bytes = (" ".repeat(pad) + doc).getBytes(StandardCharsets.UTF_8);
			try (var r = JsonReader.of(new ByteArrayInputStream(bytes), 64)) {
				assertEquals(expected, pull(r), "pad " + pad);
			}
		}
	}

	/// avaje-json 3.16: `hasNextElement()` ended an array whose first string closed on
	/// byte 4096.
	@Test
	void avajeBufferEdgeReproduction() {
		String json = "{\"a\":[\"" + "x".repeat(4088) + "\",\"y\"]}";
		try (var r = JsonReader.of(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), 4096)) {
			r.beginObject();
			assertEquals("a", r.nextName());
			r.beginArray();
			var all = new ArrayList<String>();
			while (r.hasNext()) {
				all.add(r.nextString());
			}
			r.endArray();
			r.endObject();
			assertEquals(List.of("x".repeat(4088), "y"), all);
		}
	}

	/// A file's bytes as one hex string in a backup line, well over
	/// avaje's 50,000 character limit.
	@Test
	void readsHugeStringsFromAStream() {
		byte[] data = new byte[3_000_000];
		new Random(2).nextBytes(data);
		String hex = HexFormat.of().formatHex(data);
		var lines = new StringBuilder();
		for (int i = 0; i < 3; i++) {
			lines.append("{\"log\":\"file\",\"row\":{\"id\":").append(i).append(",\"data\":\"").append(hex).append("\"}}\n");
		}
		try (var r = JsonReader.of(new ByteArrayInputStream(lines.toString().getBytes(StandardCharsets.US_ASCII)))) {
			int n = 0;
			while (r.hasNext()) {
				var row = Json.map(Json.map(r.readValue()).get("row"));
				assertEquals((long) n, row.get("id"));
				assertEquals(hex, row.get("data"));
				n++;
			}
			assertEquals(3, n);
			assertEquals(4, r.line());
		}
	}

	/// geon: multi-megabyte geometries read raw from a stream.
	@Test
	void readsHugeRawValues() {
		var w = JsonWriter.ofBytes();
		w.beginArray();
		var random = new Random(3);
		for (int i = 0; i < 200_000; i++) {
			w.beginArray().value(random.nextDouble() * 360 - 180).value(random.nextDouble() * 180 - 90).endArray();
		}
		w.endArray();
		String coordinates = w.toString();
		String doc = "{\"name\":\"Bärau\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + coordinates + "},\"x\":1}";
		try (var r = JsonReader.of(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)), 1024)) {
			r.beginObject();
			assertEquals("name", r.nextName());
			assertEquals("\"Bärau\"", r.readRaw());
			assertEquals("geometry", r.nextName());
			String geometry = r.readRaw();
			assertEquals("{\"type\":\"Polygon\",\"coordinates\":" + coordinates + "}", geometry);
			assertEquals("x", r.nextName());
			assertArrayEquals("1".getBytes(StandardCharsets.US_ASCII), r.readRawBytes());
			r.endObject();
			assertFalse(r.hasNext());
		}
	}

	@Test
	void skipsValues() {
		try (var r = JsonReader.of(new Trickle(DOCUMENTS.getFirst().getBytes(StandardCharsets.UTF_8), null), 16)) {
			r.beginObject();
			var names = new ArrayList<String>();
			while (r.hasNext()) {
				names.add(r.nextName());
				r.skipValue();
			}
			r.endObject();
			assertEquals(List.of("a", "s", "e", "u", "t", "f", "n", "o", "l", "deep"), names);
		}
	}

	@Test
	void readsJsonLines() {
		String lines = "{\"a\":1}\n{\"a\":2}\r\n\n  [3]\n\"four\"\n";
		try (var r = JsonReader.of(lines)) {
			var all = new ArrayList<@Nullable Object>();
			while (r.hasNext()) {
				all.add(r.readValue());
			}
			assertEquals(List.of(Map.of("a", 1L), Map.of("a", 2L), List.of(3L), "four"), all);
		}
	}

	@Test
	void saysWhereErrorsAre() {
		var e = assertThrows(JsonException.class, () -> {
			try (var r = JsonReader.of("{\"a\":1}\n{\"a\":2}\n{\"a\":3,,}\n")) {
				while (r.hasNext()) {
					r.readValue();
				}
			}
		});
		assertEquals("Expected a name but found ',' at line 3, column 8 (offset 23)", e.getMessage());
		e = assertThrows(JsonException.class, () -> Json.read("[1, 2"));
		assertEquals("Expected ',' or ']' but the input ended at line 1, column 6 (offset 5)", e.getMessage());
		e = assertThrows(JsonException.class, () -> Json.read("{\"a\":01}"));
		assertEquals("Invalid number '01' at line 1, column 6 (offset 5)", e.getMessage());
		e = assertThrows(JsonException.class, () -> Json.read("[1] x"));
		assertEquals("Expected the end of the input after a JSON value at line 1, column 5 (offset 4)", e.getMessage());
	}

	@Test
	void readsNumbersAsJavaDoes() {
		var random = new Random(4);
		var sb = new StringBuilder("[");
		var expected = new ArrayList<Double>();
		for (int i = 0; i < 20_000; i++) {
			String s = switch (i % 5) {
				case 0 -> Double.toString(random.nextDouble() * 360 - 180);
				case 1 -> String.format(java.util.Locale.ROOT, "%.7f", random.nextDouble() * 180 - 90);
				case 2 -> Double.toString(Double.longBitsToDouble(random.nextLong() & 0x7FEFFFFFFFFFFFFFL));
				case 3 -> random.nextInt(1000) + "." + random.nextInt(1000) + "e" + (random.nextInt(40) - 20);
				default -> Long.toString(random.nextLong() >> random.nextInt(64));
			};
			if (i > 0) {
				sb.append(',');
			}
			sb.append(s);
			expected.add(Double.parseDouble(s));
		}
		sb.append(']');
		try (var r = JsonReader.of(sb.toString())) {
			r.beginArray();
			for (double d : expected) {
				assertEquals(d, r.nextDouble());
			}
			r.endArray();
		}
	}

	@Test
	void readsLongs() {
		try (var r = JsonReader.of("[9223372036854775807,-9223372036854775808,1.0,1e3,100000000000000000000,1.5]")) {
			r.beginArray();
			assertEquals(Long.MAX_VALUE, r.nextLong());
			assertEquals(Long.MIN_VALUE, r.nextLong());
			assertEquals(1, r.nextLong());
			assertEquals(1000, r.nextInt());
			assertThrows(JsonException.class, r::nextLong);
			r.skipValue();
			assertThrows(JsonException.class, r::nextLong);
			assertEquals("1.5", r.nextString());
			r.endArray();
		}
		assertEquals(new BigInteger("100000000000000000000"), Json.read("100000000000000000000"));
		assertEquals(10000000000000000L, Json.read("10000000000000000"));
	}

	@Test
	void replacesLoneSurrogates() {
		assertEquals("a�b�c", Json.read("\"a\\ud800b\\udc00c\""));
		assertEquals("��", Json.read("\"\\ud800\\ud800\""));
	}

	@Test
	void doesNotChangeTheCallersArray() {
		byte[] bytes = "[\"a\\nb\",1]".getBytes(StandardCharsets.US_ASCII);
		byte[] copy = bytes.clone();
		Json.read(bytes);
		assertArrayEquals(copy, bytes);
		assertEquals(List.of("b"), Json.read(Arrays.copyOfRange("x[\"b\"]x".getBytes(StandardCharsets.US_ASCII), 1, 6)));
		try (var r = JsonReader.of("x[\"b\"]x".getBytes(StandardCharsets.US_ASCII), 1, 5)) {
			assertEquals(List.of("b"), r.readValue());
			assertFalse(r.hasNext());
		}
	}

	static @Nullable Object readTree(JsonReader r) {
		try (r) {
			Object value = r.readValue();
			assertFalse(r.hasNext());
			return value;
		}
	}

	/// Builds a tree with the pull API only.
	static @Nullable Object pull(JsonReader r) {
		return switch (r.peek()) {
			case BEGIN_OBJECT -> {
				var map = new LinkedHashMap<String, @Nullable Object>();
				r.beginObject();
				while (r.hasNext()) {
					map.put(r.nextName(), pull(r));
				}
				r.endObject();
				yield map;
			}
			case BEGIN_ARRAY -> {
				var list = new ArrayList<@Nullable Object>();
				r.beginArray();
				while (r.hasNext()) {
					list.add(pull(r));
				}
				r.endArray();
				yield list;
			}
			case STRING -> r.nextString();
			case NUMBER -> {
				String s = r.nextString();
				if (s.contains(".") || s.contains("e") || s.contains("E")) {
					yield Double.parseDouble(s);
				}
				var big = new BigInteger(s);
				yield big.bitLength() < 64 ? (Object) big.longValue() : big;
			}
			case BOOLEAN -> r.nextBoolean();
			case NULL -> {
				r.nextNull();
				yield null;
			}
			default -> r.readValue(); // throws, as the tree does
		};
	}

	/// Gives a byte at a time, or random small chunks.
	static final class Trickle extends FilterInputStream {

		private final @Nullable Random random;

		Trickle(byte[] bytes, @Nullable Random random) {
			super(new ByteArrayInputStream(bytes));
			this.random = random;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = random == null ? 1 : 1 + random.nextInt(Math.min(len, 40));
			return super.read(b, off, Math.min(len, n));
		}

	}

	static InputStream stream(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}

}
