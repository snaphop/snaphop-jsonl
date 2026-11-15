package ai.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/// Edges that mutation testing found untested.
class WriterEdgesTest {

	/// The bytes a string should be written as, worked out a char at a time.
	static byte[] reference(String s, boolean escape) {
		var out = new ByteArrayOutputStream();
		if (escape) {
			out.write('"');
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			String piece;
			if (escape && (c == '"' || c == '\\')) {
				piece = "\\" + c;
			}
			else if (escape && c < 0x20) {
				piece = switch (c) {
					case '\b' -> "\\b";
					case '\f' -> "\\f";
					case '\n' -> "\\n";
					case '\r' -> "\\r";
					case '\t' -> "\\t";
					default -> String.format("\\u%04x", (int) c);
				};
			}
			else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
				piece = s.substring(i, i + 2);
				i++;
			}
			else if (Character.isSurrogate(c)) {
				piece = "�";
			}
			else {
				piece = String.valueOf(c);
			}
			out.writeBytes(piece.getBytes(StandardCharsets.UTF_8));
		}
		if (escape) {
			out.write('"');
		}
		return out.toByteArray();
	}

	static final String[] SPECIALS = { "\"", "\\", "\n", "\t", "\b", "\f", "\r", "\u0000", "\u0001", "\u001f", "?", "\u007f",
			"\u0080", "߿", "ࠀ", "￿", "\ud800", "\udc00", "𐀀", "􏿿", "é", "/" };

	/// Every special character at every place in strings of every length to past the
	/// long-string path, both all ASCII around it and not.
	@Test
	void writesEveryCharacterAtEveryPlace() {
		for (String around : List.of("x", "é")) {
			for (int n = 0; n <= 80; n++) {
				for (String special : SPECIALS) {
					for (int at = 0; at <= n; at += Math.max(1, n / 9)) {
						String s = around.repeat(at) + special + around.repeat(n - at);
						assertArrayEquals(reference(s, true), Json.writeBytes(s), () -> "string " + FuzzTest.describe(s));
						assertArrayEquals(reference(s, false), JsonWriter.ofBytes().rawValue(s).toBytes(),
								() -> "raw " + FuzzTest.describe(s));
						var w = JsonWriter.ofBytes();
						w.beginObject().name(s).value(1).endObject();
						byte[] name = reference(s, true);
						assertEquals("{" + new String(name, StandardCharsets.UTF_8) + ":1}", w.toString());
					}
				}
			}
		}
	}

	@Test
	void writesRandomStringsAsTheReferenceDoes() {
		var r = new Random(11);
		for (int i = 0; i < 20_000; i++) {
			var sb = new StringBuilder();
			int n = r.nextInt(r.nextBoolean() ? 10 : 400);
			while (sb.length() < n) {
				sb.append(r.nextInt(4) == 0 ? SPECIALS[r.nextInt(SPECIALS.length)] : (char) ('a' + r.nextInt(26)));
			}
			String s = sb.toString();
			assertArrayEquals(reference(s, true), Json.writeBytes(s), () -> FuzzTest.describe(s));
		}
	}

	/// A document written through every small buffer size is the same as written to
	/// memory, so every write meets the buffer's end somewhere.
	@Test
	void writesThroughEveryBufferSize() {
		var tree = new LinkedHashMap<String, Object>();
		tree.put("short", "a\"b");
		tree.put("long ascii " + "k".repeat(70), "v".repeat(100) + "\n" + "?".repeat(10));
		tree.put("long", "安".repeat(50) + "😀" + "\ud800");
		tree.put("numbers", List.of(Long.MIN_VALUE, 0L, 12345L, 1.5e-300, -0.25));
		tree.put("nested", List.of(List.of(), new LinkedHashMap<>(), List.of(List.of(true, false))));
		tree.put("null", null);
		for (String indent : List.of("", "  ", "\t")) {
			byte[] expected = JsonWriter.ofBytes().indent(indent).value(tree).value("next line").toBytes();
			for (int size = 16; size <= 90; size++) {
				var out = new ByteArrayOutputStream();
				try (var w = JsonWriter.of(out, size).indent(indent)) {
					w.value(tree).value("next line");
					w.rawValue(new byte[] { '[', '1', ']' }).rawValue("x".repeat(size + 1).getBytes(StandardCharsets.US_ASCII), 1, size);
				}
				byte[] tail = ("\n[1]\n" + "x".repeat(size)).getBytes(StandardCharsets.US_ASCII);
				byte[] all = new byte[expected.length + tail.length];
				System.arraycopy(expected, 0, all, 0, expected.length);
				System.arraycopy(tail, 0, all, expected.length, tail.length);
				assertArrayEquals(all, out.toByteArray(), "buffer " + size + " indent '" + indent + "'");
			}
		}
	}

	/// Random trees with raw numbers of every length in them, so that every kind of
	/// write starts with the buffer exactly full at some buffer size.
	@Test
	void writesWhenTheBufferIsExactlyFull() {
		var r = new Random(13);
		for (int i = 0; i < 400; i++) {
			Object tree = withRaws(r, FuzzTest.Gen.value(r, 2), 0);
			Object second = withRaws(r, FuzzTest.Gen.value(r, 2), 0);
			String indent = r.nextInt(4) == 0 ? " " : "";
			byte[] expected = JsonWriter.ofBytes().indent(indent).value(tree).value(second).toBytes();
			for (int size = 16; size <= 64; size++) {
				var out = new ByteArrayOutputStream();
				try (var w = JsonWriter.of(out, size).indent(indent)) {
					w.value(tree).value(second);
				}
				assertArrayEquals(expected, out.toByteArray(), "buffer " + size + " tree " + i);
			}
		}
	}

	/// The tree with some values replaced by raw numbers of random length.
	static @org.jspecify.annotations.Nullable Object withRaws(Random r, @org.jspecify.annotations.Nullable Object v, int depth) {
		if (r.nextInt(3) == 0) {
			String digits = "1" + "0".repeat(r.nextInt(70));
			return (JsonWritable) w -> w.rawValue(digits);
		}
		return switch (v) {
			case java.util.Map<?, ?> m -> {
				var out = new LinkedHashMap<String, @org.jspecify.annotations.Nullable Object>();
				m.forEach((k, x) -> out.put(r.nextBoolean() ? (String) k : "n".repeat(r.nextInt(40)) + k, withRaws(r, x, depth + 1)));
				yield out;
			}
			case List<?> l -> {
				var out = new ArrayList<@org.jspecify.annotations.Nullable Object>();
				l.forEach(x -> out.add(withRaws(r, x, depth + 1)));
				yield out;
			}
			case null -> null;
			default -> v;
		};
	}

	@Test
	void flushesAndCloses() {
		var calls = new ArrayList<String>();
		var out = new OutputStream() {
			@Override
			public void write(int b) {
				calls.add("write");
			}

			@Override
			public void write(byte[] b, int off, int len) {
				calls.add("write " + new String(b, off, len, StandardCharsets.UTF_8));
			}

			@Override
			public void flush() {
				calls.add("flush");
			}

			@Override
			public void close() {
				calls.add("close");
			}
		};
		var w = JsonWriter.of(out);
		w.value(1);
		assertEquals(List.of(), calls);
		w.flush();
		assertEquals(List.of("write 1", "flush"), calls);
		w.flush();
		assertEquals(List.of("write 1", "flush", "flush"), calls);
		w.value(2).close();
		assertEquals(List.of("write 1", "flush", "flush", "write \n2", "flush", "close"), calls);
		assertTrue(w.toString().startsWith("JsonWriter["));
		assertThrows(IllegalStateException.class, w::toBytes);
		assertThrows(IllegalStateException.class, w::size);
		assertThrows(IllegalStateException.class, w::reset);
		JsonWriter.ofBytes().close();
		JsonWriter.ofBytes().flush();
	}

	@Test
	void reportsStreamFailures() {
		var out = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("full");
			}

			@Override
			public void flush() throws IOException {
				throw new IOException("stuck");
			}

			@Override
			public void close() throws IOException {
				throw new IOException("jammed");
			}
		};
		assertEquals("Can't write JSON: full", assertThrows(JsonException.class, () -> JsonWriter.of(out).value(1).flush()).getMessage());
		assertEquals("Can't write JSON: stuck", assertThrows(JsonException.class, () -> JsonWriter.of(out).flush()).getMessage());
		assertEquals("Can't write JSON: stuck", assertThrows(JsonException.class, () -> JsonWriter.of(out).close()).getMessage());
		var closing = new ByteArrayOutputStream() {
			@Override
			public void close() throws IOException {
				throw new IOException("jammed");
			}
		};
		assertEquals("Can't write JSON: jammed", assertThrows(JsonException.class, () -> JsonWriter.of(closing).close()).getMessage());
		assertEquals("Can't write JSON: full", assertThrows(JsonException.class,
				() -> JsonWriter.of(out, 16).rawValue(new byte[17])).getMessage());
		assertThrows(IllegalArgumentException.class, () -> JsonWriter.of(out, 15));
	}

	@Test
	void writesToMemory() {
		var w = JsonWriter.ofBytes();
		w.value((String) null).value("a");
		assertEquals(8, w.size());
		assertEquals("null\n\"a\"", w.toString());
		w.reset();
		assertEquals(0, w.size());
		w.value(1);
		assertEquals("1", w.toString(), "no new line after a reset");
		w.reset();
		w.beginArray();
		w.reset();
		w.value(2);
		assertEquals("2", w.toString(), "nesting forgotten");
		assertThrows(IndexOutOfBoundsException.class, () -> JsonWriter.ofBytes().rawValue(new byte[2], 1, 2));
		assertEquals("[1,2]", JsonWriter.ofBytes().beginArray().rawValue(new byte[] { 'x', '1' }, 1, 1)
			.rawValue("2".getBytes(StandardCharsets.US_ASCII)).endArray().toString());
		var big = JsonWriter.ofBytes();
		big.value("y".repeat(100_000));
		assertEquals(100_002, big.toBytes().length);
		var out = new ByteArrayOutputStream();
		Json.write(List.of(1L), out);
		assertEquals("[1]", out.toString(StandardCharsets.UTF_8));
		assertEquals("{\n  \"a\": 1\n}", JsonWriter.ofBytes().indent("  ").indent("  ").value(java.util.Map.of("a", 1L)).toString());
		assertEquals("{\"a\":1}", JsonWriter.ofBytes().indent("  ").indent("").value(java.util.Map.of("a", 1L)).toString());
	}

	@Test
	void checksStructure() {
		List<Consumer<JsonWriter>> misuses = List.of(w -> w.endObject(), w -> w.endArray(), w -> w.beginArray().endObject(),
				w -> w.beginObject().endArray(), w -> w.beginObject().name("a").name("b"), w -> w.beginObject().name("a").endObject(),
				w -> w.name("top"), w -> w.beginObject().beginArray(), w -> w.beginObject().nullValue(),
				w -> w.beginObject().rawValue("1"), w -> w.beginArray().name("a"));
		for (var misuse : misuses) {
			assertThrows(JsonException.class, () -> misuse.accept(JsonWriter.ofBytes()));
		}
		assertEquals("[{\"a\":[]}]", JsonWriter.ofBytes().beginArray().beginObject().name("a").beginArray().endArray().endObject()
			.endArray().toString());
	}

	@Test
	@SuppressWarnings({ "UnnecessaryStringBuilder", "JdkObsolete" }) // a CharSequence and a list without RandomAccess
	void writesEveryKindOfValue() {
		var items = new ArrayList<Object>();
		items.add((short) 3);
		items.add((byte) -4);
		items.add(1.5f);
		items.add(12);
		items.add(new java.math.BigInteger("123456789012345678901234567890"));
		items.add(new StringBuilder("sb"));
		items.add(new java.util.LinkedList<>(List.of(1L, 2L)));
		items.add(new java.util.TreeSet<>(List.of("b", "a")));
		items.add(new Object[] { null, "x" });
		assertEquals("[3,-4,1.5,12,123456789012345678901234567890,\"sb\",[1,2],[\"a\",\"b\"],[null,\"x\"]]", Json.write(items));
		assertEquals("null", Json.write(null));
		assertThrows(JsonException.class, () -> JsonWriter.ofBytes().value(Double.POSITIVE_INFINITY));
		assertThrows(JsonException.class, () -> JsonWriter.ofBytes().value(Double.NEGATIVE_INFINITY));
		assertThrows(JsonException.class, () -> Json.write(new java.util.concurrent.atomic.AtomicLong(1)));
		assertEquals("Can't write a java.lang.Object as JSON", assertThrows(JsonException.class, () -> Json.write(new Object())).getMessage());
	}

}
