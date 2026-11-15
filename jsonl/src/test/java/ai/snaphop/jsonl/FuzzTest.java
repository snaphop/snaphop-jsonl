package ai.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/// Random documents, and random damage to them, checked against Jackson and against
/// jsonl itself reading the same bytes another way.
///
/// `-Dfuzz.iterations=N` sets how many of each (default 3,000) and `-Dfuzz.seed=S`
/// the first seed; a failure names the seed that reproduces it.
class FuzzTest {

	static final int ITERATIONS = Integer.getInteger("fuzz.iterations", 3_000);

	static final long SEED = Long.getLong("fuzz.seed", 20261115L);

	static final JsonMapper JACKSON = JsonMapper.builder()
		.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	/// Generated trees, formatted at random, read whole and in random chunks.
	@Test
	void generatedDocuments() {
		for (int i = 0; i < ITERATIONS; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			Object tree = Gen.value(r, 0);
			String json = Gen.format(r, tree);
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			try {
				assertSame(tree, Json.read(bytes), "bytes");
				assertSame(tree, JsonReaderTest.readTree(JsonReader.of(new Chunks(bytes, r), 16 + r.nextInt(100))), "stream");
				assertSame(tree, JACKSON.readValue(Json.writeBytes(tree), Object.class), "written, read by Jackson");
				assertSame(tree, Json.read(Json.write(tree)), "written, read back");
			}
			catch (AssertionError | RuntimeException e) {
				throw new AssertionError("seed " + seed + ": " + e.getMessage() + "\nin " + abbreviate(json), e);
			}
		}
	}

	/// Damaged documents: jsonl and Jackson agree on whether they're JSON and on their
	/// value, and jsonl reads bytes and streams alike, down to its error messages.
	@Test
	void damagedDocuments() {
		int rejected = 0;
		for (int i = 0; i < ITERATIONS * 4; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			byte[] damaged = Gen.format(r, Gen.value(r, 0)).getBytes(StandardCharsets.UTF_8);
			for (int m = 1 + r.nextInt(3); m > 0; m--) {
				damaged = Gen.damage(r, damaged);
			}
			byte[] bytes = damaged;
			Outcome ours = Outcome.of(() -> Json.read(bytes));
			Outcome stream = Outcome.of(() -> Json.read(new Chunks(bytes, r)));
			Outcome trickle = Outcome.of(() -> Json.read(new JsonReaderTest.Trickle(bytes, null)));
			Outcome pulled = Outcome.of(() -> pullOne(JsonReader.of(new Chunks(bytes, r), 16)));
			assertEquals(ours, pulled, () -> "seed " + seed + " tree vs pull: " + abbreviate(new String(bytes, StandardCharsets.UTF_8)));
			if (ours.error == null) {
				Outcome skipped = Outcome.of(() -> {
					try (var reader = JsonReader.of(bytes)) {
						reader.skipValue();
						return reader.peek() == JsonReader.Token.END ? "skipped" : "trailing";
					}
				});
				assertEquals("skipped", skipped.value, () -> "seed " + seed + " skip: " + skipped);
			}
			String json = abbreviate(new String(bytes, StandardCharsets.UTF_8));
			assertEquals(ours, stream, () -> "seed " + seed + " bytes vs stream: " + json);
			assertEquals(ours, trickle, () -> "seed " + seed + " bytes vs trickle: " + json);
			if (!validUtf8(bytes)) {
				continue; // Jackson rejects invalid UTF-8; jsonl replaces it
			}
			Outcome theirs = Outcome.of(() -> JACKSON.readValue(bytes, Object.class));
			if (ours.error != null) {
				rejected++;
			}
			if (ours.error != null && theirs.error == null && indexOf(bytes, (byte) 0) >= 0) {
				continue; // Jackson takes a NUL byte as the end of the input
			}
			if (theirs.error != null && theirs.error.contains("surrogate") && ours.error == null) {
				continue; // jsonl replaces escaped lone surrogates; Jackson rejects them in names
			}
			if ((ours.error == null) != (theirs.error == null) || (ours.error == null && !Objects.equals(ours.value, theirs.value))) {
				fail("seed " + seed + ": jsonl " + ours + ", Jackson " + theirs + ": " + json);
			}
		}
		System.out.println("damagedDocuments: " + rejected + " of " + ITERATIONS * 4 + " rejected by both");
	}

	/// The pull API, skipping and raw reads agree with the tree at every step.
	@Test
	void pullAgreesWithTree() {
		for (int i = 0; i < ITERATIONS; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			Object tree = Gen.value(r, 0);
			byte[] bytes = Gen.format(r, tree).getBytes(StandardCharsets.UTF_8);
			try (var reader = JsonReader.of(new Chunks(bytes, r), 16 + r.nextInt(50))) {
				assertSame(tree, pull(reader, r), "seed " + seed);
				assertEquals(JsonReader.Token.END, reader.peek());
			}
		}
	}

	/// One value by the pull API alone, as [Json#read(byte[])] reads it.
	static @Nullable Object pullOne(JsonReader r) {
		try (r) {
			Object value = JsonReaderTest.pull(r);
			if (!r.atEnd()) {
				throw new JsonException("Expected the end of the input after a JSON value at " + r.location());
			}
			return value;
		}
	}

	/// Reads a value with a random mix of the pull API, readValue, readRaw and skip;
	/// skipped values come back as the marker "<skipped>" only where the tree is
	/// compared loosely, so here everything is read but by different means.
	static @Nullable Object pull(JsonReader r, Random random) {
		int how = random.nextInt(10);
		if (how == 0) {
			return r.readValue();
		}
		if (how == 1) {
			return Json.read(r.readRaw());
		}
		if (how == 2) {
			return Json.read(r.readRawBytes());
		}
		return switch (r.peek()) {
			case BEGIN_OBJECT -> {
				var map = new LinkedHashMap<String, @Nullable Object>();
				r.beginObject();
				while (r.hasNext()) {
					String name = r.nextName();
					map.put(name, pull(r, random));
				}
				r.endObject();
				yield map;
			}
			case BEGIN_ARRAY -> {
				var list = new ArrayList<@Nullable Object>();
				r.beginArray();
				while (r.hasNext()) {
					list.add(pull(r, random));
				}
				r.endArray();
				yield list;
			}
			case STRING -> r.nextString();
			case NUMBER -> {
				// As the tree reads the text: a plain integer exactly, otherwise a double.
				String text = random.nextBoolean() ? r.nextString() : r.readRaw();
				yield text.contains(".") || text.contains("e") || text.contains("E") ? (Object) Double.parseDouble(text)
						: new BigInteger(text);
			}
			case BOOLEAN -> r.nextBoolean();
			case NULL -> {
				if (random.nextBoolean()) {
					r.nextNull();
				}
				else if (!r.nextIfNull()) {
					throw new AssertionError("nextIfNull");
				}
				yield null;
			}
			default -> throw new AssertionError(r.peek());
		};
	}

	/// Skipping any member leaves the rest of the object as the tree has it.
	@Test
	void skipAgreesWithTree() {
		for (int i = 0; i < ITERATIONS; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			var tree = new LinkedHashMap<String, @Nullable Object>();
			for (int n = r.nextInt(8); n >= 0; n--) {
				tree.put("k" + n, Gen.value(r, 1));
			}
			byte[] bytes = Gen.format(r, tree).getBytes(StandardCharsets.UTF_8);
			var kept = new LinkedHashMap<String, @Nullable Object>();
			try (var reader = JsonReader.of(new Chunks(bytes, r), 16)) {
				reader.beginObject();
				while (reader.hasNext()) {
					if (r.nextBoolean()) {
						reader.skipValue(); // the name and its value
						continue;
					}
					String name = reader.nextName();
					if (r.nextBoolean()) {
						reader.skipValue();
					}
					else {
						kept.put(name, reader.readValue());
					}
				}
				reader.endObject();
			}
			for (var e : kept.entrySet()) {
				assertSame(tree.get(e.getKey()), e.getValue(), "seed " + seed + " " + e.getKey());
			}
		}
	}

	/// Number text read by every number method agrees with the JDK's parsing of it.
	@Test
	void numbers() {
		for (int i = 0; i < ITERATIONS * 10; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			String text = numberText(r);
			try {
				String json = "[" + text + "," + text + "," + text + "," + text + "]";
				try (var reader = JsonReader.of(new Chunks(json.getBytes(StandardCharsets.US_ASCII), r), 16)) {
					reader.beginArray();
					assertEquals(new BigDecimal(text), reader.nextBigDecimal());
					assertEquals(Double.parseDouble(text), reader.nextDouble());
					Long expected;
					try {
						expected = new BigDecimal(text).longValueExact();
					}
					catch (ArithmeticException e) {
						expected = null;
					}
					if (expected != null) {
						assertEquals(expected, reader.nextLong());
					}
					else {
						org.junit.jupiter.api.Assertions.assertThrows(JsonException.class, reader::nextLong);
						reader.skipValue();
					}
					assertEquals(text, reader.nextString());
					reader.endArray();
				}
			}
			catch (AssertionError | RuntimeException e) {
				throw new AssertionError("seed " + seed + ": " + text, e);
			}
		}
	}

	static String numberText(Random r) {
		var sb = new StringBuilder();
		if (r.nextBoolean()) {
			sb.append('-');
		}
		int intDigits = 1 + r.nextInt(r.nextInt(5) == 0 ? 30 : 8);
		sb.append(r.nextInt(4) == 0 ? '0' : (char) ('1' + r.nextInt(9)));
		if (sb.charAt(sb.length() - 1) != '0') {
			for (int i = 1; i < intDigits; i++) {
				sb.append((char) ('0' + r.nextInt(10)));
			}
		}
		if (r.nextBoolean()) {
			sb.append('.');
			for (int i = 1 + r.nextInt(r.nextInt(5) == 0 ? 30 : 8); i > 0; i--) {
				sb.append(r.nextInt(3) == 0 ? '0' : (char) ('0' + r.nextInt(10)));
			}
		}
		if (r.nextInt(3) == 0) {
			sb.append(r.nextBoolean() ? 'e' : 'E');
			int sign = r.nextInt(3);
			if (sign > 0) {
				sb.append(sign == 1 ? '+' : '-');
			}
			sb.append(r.nextInt(r.nextInt(4) == 0 ? 400 : 30));
		}
		return sb.toString();
	}

	// Comparing

	/// Numbers as BigDecimal (doubles by their exact value), strings with lone
	/// surrogates replaced, since jsonl replaces them on reading.
	static Object normal(@Nullable Object v) {
		return switch (v) {
			case Map<?, ?> m -> {
				var out = new LinkedHashMap<String, Object>();
				m.forEach((k, x) -> out.put(fixSurrogates(String.valueOf(k)), normal(x)));
				yield out;
			}
			case List<?> l -> l.stream().map(FuzzTest::normal).toList();
			case Double d -> exact(d);
			case Float d -> exact(d.doubleValue());
			case BigDecimal d -> {
				// As a parser reads its text: a fraction or exponent makes it a double.
				String text = d.toString();
				yield text.contains(".") || text.contains("E") ? exact(d.doubleValue()) : d.stripTrailingZeros();
			}
			case Long n -> new BigDecimal(n).stripTrailingZeros();
			case Integer n -> new BigDecimal(n).stripTrailingZeros();
			case BigInteger n -> new BigDecimal(n).stripTrailingZeros();
			case String s -> fixSurrogates(s);
			case null -> "<null>";
			default -> v;
		};
	}

	static Object exact(double d) {
		return Double.isFinite(d) ? new BigDecimal(d).stripTrailingZeros() : d;
	}

	/// Where two normal values first differ, for messages.
	static String difference(@Nullable Object expected, @Nullable Object actual, String path) {
		if (expected instanceof Map<?, ?> e && actual instanceof Map<?, ?> a) {
			if (!e.keySet().equals(a.keySet())) {
				return path + ": keys " + e.keySet() + " vs " + a.keySet();
			}
			for (var k : e.keySet()) {
				String d = difference(e.get(k), a.get(k), path + "." + k);
				if (!d.isEmpty()) {
					return d;
				}
			}
			return "";
		}
		if (expected instanceof List<?> e && actual instanceof List<?> a) {
			if (e.size() != a.size()) {
				return path + ": " + e.size() + " elements vs " + a.size();
			}
			for (int i = 0; i < e.size(); i++) {
				String d = difference(e.get(i), a.get(i), path + "[" + i + "]");
				if (!d.isEmpty()) {
					return d;
				}
			}
			return "";
		}
		if (Objects.equals(expected, actual)) {
			return "";
		}
		if (expected instanceof String e && actual instanceof String a) {
			int i = 0;
			while (i < e.length() && i < a.length() && e.charAt(i) == a.charAt(i)) {
				i++;
			}
			int from = Math.max(0, i - 10);
			return path + " at char " + i + ": " + describe(e.substring(from, Math.min(e.length(), i + 10))) + " vs "
					+ describe(a.substring(from, Math.min(a.length(), i + 10)));
		}
		return path + ": " + describe(expected) + " vs " + describe(actual);
	}

	static String describe(@Nullable Object v) {
		if (v instanceof String s) {
			var sb = new StringBuilder("\"");
			s.chars().limit(80).forEach(c -> sb.append(c < 0x20 || c > 0x7E ? String.format("\\u%04x", c) : String.valueOf((char) c)));
			return sb.append(s.length() > 80 ? "...\" (" + s.length() + ")" : "\"").toString();
		}
		return v + (v == null ? "" : " (" + v.getClass().getSimpleName() + ")");
	}

	static void assertSame(@Nullable Object tree, @Nullable Object read, String context) {
		Object expected = normal(tree);
		Object actual = normal(read);
		if (!expected.equals(actual)) {
			fail(context + ": " + difference(expected, actual, "$"));
		}
	}

	static String fixSurrogates(String s) {
		var sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
				sb.append(c).append(s.charAt(++i));
			}
			else {
				sb.append(Character.isSurrogate(c) ? '�' : c);
			}
		}
		return sb.toString();
	}

	static int indexOf(byte[] bytes, byte b) {
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] == b) {
				return i;
			}
		}
		return -1;
	}

	static boolean validUtf8(byte[] bytes) {
		try {
			StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes));
			return true;
		}
		catch (CharacterCodingException e) {
			return false;
		}
	}

	static String abbreviate(String s) {
		return s.length() <= 300 ? s : s.substring(0, 300) + "... (" + s.length() + " chars)";
	}

	/// A value, or the message of the error reading it.
	record Outcome(@Nullable Object value, @Nullable String error) {

		interface Read {
			@Nullable Object read() throws Exception;
		}

		static Outcome of(Read read) {
			try {
				return new Outcome(normal(read.read()), null);
			}
			catch (JsonException | JacksonException e) {
				return new Outcome(null, e.getMessage());
			}
			catch (Exception | StackOverflowError e) {
				throw new AssertionError("Not a JSON error", e);
			}
		}

		@Override
		public String toString() {
			return error == null ? "read " + abbreviate(String.valueOf(value)) : "rejected (" + abbreviate(error) + ")";
		}

	}

	/// An input stream giving random chunks.
	static final class Chunks extends ByteArrayInputStream {

		private final Random random;

		Chunks(byte[] bytes, Random random) {
			super(bytes);
			this.random = new Random(random.nextLong());
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) {
			return super.read(b, off, Math.min(len, 1 + random.nextInt(random.nextBoolean() ? 8 : 300)));
		}

	}

	/// Random JSON: values, formatting and damage.
	static final class Gen {

		private Gen() {
		}

		static final String[] PIECES = { "a", "Z", " ", "\"", "\\", "/", "\n", "\t", "\r", "\b", "\f", "\u0000",
				"\u001f", "\u007f", "é", "ß", "安", "東京", "😀", "\uD800", "\uDFFF", " ", "￿", "﻿" };

		static @Nullable Object value(Random r, int depth) {
			int kind = r.nextInt(depth > 6 ? 6 : 9);
			return switch (kind) {
				case 0 -> null;
				case 1 -> r.nextBoolean();
				case 2 -> string(r);
				case 3 -> number(r);
				case 4 -> (long) r.nextInt(100) - 50;
				case 5 -> r.nextDouble() * Math.pow(10, r.nextInt(40) - 20);
				case 6, 7 -> {
					var list = new ArrayList<@Nullable Object>();
					for (int n = r.nextInt(6); n > 0; n--) {
						list.add(value(r, depth + 1));
					}
					yield list;
				}
				default -> {
					var map = new LinkedHashMap<String, @Nullable Object>();
					for (int n = r.nextInt(6); n > 0; n--) {
						map.put(r.nextInt(4) == 0 ? string(r) : "k" + r.nextInt(20), value(r, depth + 1));
					}
					yield map;
				}
			};
		}

		static String string(Random r) {
			int n = switch (r.nextInt(10)) {
				case 0 -> 0;
				case 1 -> 50 + r.nextInt(5_000);
				default -> r.nextInt(12);
			};
			var sb = new StringBuilder();
			for (int i = 0; i < n; i++) {
				if (r.nextInt(3) == 0) {
					sb.append(PIECES[r.nextInt(PIECES.length)]);
				}
				else {
					sb.append((char) ('a' + r.nextInt(26)));
				}
			}
			return sb.toString();
		}

		static Object number(Random r) {
			return switch (r.nextInt(8)) {
				case 0 -> r.nextLong();
				case 1 -> new BigInteger(70 + r.nextInt(60), r);
				case 2 -> Double.longBitsToDouble(r.nextLong() & 0x7FEFFFFFFFFFFFFFL) * (r.nextBoolean() ? 1 : -1);
				case 3 -> new BigDecimal(new BigInteger(1 + r.nextInt(80), r), r.nextInt(60) - 30);
				case 4 -> 0L;
				case 5 -> Math.round(r.nextGaussian() * 1e9) / 1e7;
				case 6 -> Long.MIN_VALUE + r.nextInt(3);
				default -> (double) r.nextInt(1000);
			};
		}

		/// JSON text for a tree, with random whitespace and escapes where JSON allows.
		static String format(Random r, @Nullable Object tree) {
			var sb = new StringBuilder();
			ws(r, sb);
			write(r, sb, tree);
			ws(r, sb);
			return sb.toString();
		}

		private static void ws(Random r, StringBuilder sb) {
			if (r.nextInt(3) == 0) {
				for (int n = r.nextInt(4); n > 0; n--) {
					sb.append(" \t\n\r".charAt(r.nextInt(4)));
				}
			}
		}

		private static void write(Random r, StringBuilder sb, @Nullable Object v) {
			switch (v) {
				case null -> sb.append("null");
				case Map<?, ?> m -> {
					sb.append('{');
					boolean first = true;
					for (var e : m.entrySet()) {
						if (!first) {
							ws(r, sb);
							sb.append(',');
						}
						first = false;
						ws(r, sb);
						string(r, sb, (String) e.getKey());
						ws(r, sb);
						sb.append(':');
						ws(r, sb);
						write(r, sb, e.getValue());
					}
					ws(r, sb);
					sb.append('}');
				}
				case List<?> l -> {
					sb.append('[');
					for (int i = 0; i < l.size(); i++) {
						if (i > 0) {
							ws(r, sb);
							sb.append(',');
						}
						ws(r, sb);
						write(r, sb, l.get(i));
					}
					ws(r, sb);
					sb.append(']');
				}
				case String s -> string(r, sb, s);
				case Double d -> sb.append(switch (r.nextInt(3)) {
					case 0 -> new BigDecimal(d).toString();
					case 1 -> String.format(java.util.Locale.ROOT, "%.17e", d).replace("e+", "E+");
					default -> d.toString();
				});
				default -> sb.append(v);
			}
		}

		/// A string with random but valid escaping.
		private static void string(Random r, StringBuilder sb, String s) {
			sb.append('"');
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				boolean must = c == '"' || c == '\\' || c < 0x20 || (Character.isSurrogate(c) && !paired(s, i));
				if (Character.isHighSurrogate(c) && paired(s, i)) {
					// A pair is escaped whole or not at all.
					if (r.nextInt(20) == 0) {
						sb.append(String.format("\\u%04x\\u%04X", (int) c, (int) s.charAt(i + 1)));
					}
					else {
						sb.append(c).append(s.charAt(i + 1));
					}
					i++;
				}
				else if (must || r.nextInt(20) == 0) {
					switch (c) {
						case '"' -> sb.append("\\\"");
						case '\\' -> sb.append("\\\\");
						case '\n' -> sb.append(r.nextBoolean() ? "\\n" : "\\u000a");
						case '/' -> sb.append("\\/");
						default -> sb.append(String.format(r.nextBoolean() ? "\\u%04x" : "\\u%04X", (int) c));
					}
				}
				else {
					sb.append(c);
				}
			}
			sb.append('"');
		}

		private static boolean paired(String s, int i) {
			char c = s.charAt(i);
			return Character.isHighSurrogate(c) ? i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))
					: i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
		}

		static final byte[][] TOKENS = tokens("{", "}", "[", "]", ",", ":", "\"", "\\", "\\u", "-", "0", ".", "e", "+",
				"true", "nul", " ", "\n", "\u0000", "\u00C3", "\uFEFF");

		/// UTF-8, but U+00C3 alone as its first byte: a truncated sequence.
		private static byte[][] tokens(String... all) {
			byte[][] out = new byte[all.length][];
			for (int i = 0; i < all.length; i++) {
				out[i] = all[i].equals("\u00C3") ? new byte[] { (byte) 0xC3 } : all[i].getBytes(StandardCharsets.UTF_8);
			}
			return out;
		}

		static byte[] damage(Random r, byte[] b) {
			if (b.length == 0) {
				return TOKENS[r.nextInt(TOKENS.length)];
			}
			int at = r.nextInt(b.length);
			return switch (r.nextInt(6)) {
				case 0 -> { // delete a run
					int n = 1 + r.nextInt(Math.min(4, b.length - at));
					byte[] out = new byte[b.length - n];
					System.arraycopy(b, 0, out, 0, at);
					System.arraycopy(b, at + n, out, at, b.length - at - n);
					yield out;
				}
				case 1 -> { // insert a token
					byte[] t = TOKENS[r.nextInt(TOKENS.length)];
					byte[] out = new byte[b.length + t.length];
					System.arraycopy(b, 0, out, 0, at);
					System.arraycopy(t, 0, out, at, t.length);
					System.arraycopy(b, at, out, at + t.length, b.length - at);
					yield out;
				}
				case 2 -> { // replace a byte
					byte[] out = b.clone();
					out[at] = TOKENS[r.nextInt(TOKENS.length)][0];
					yield out;
				}
				case 3 -> { // flip a bit
					byte[] out = b.clone();
					out[at] ^= (byte) (1 << r.nextInt(8));
					yield out;
				}
				case 4 -> Arrays.copyOf(b, at); // truncate
				default -> { // duplicate a run
					int n = 1 + r.nextInt(Math.min(20, b.length - at));
					byte[] out = new byte[b.length + n];
					System.arraycopy(b, 0, out, 0, at + n);
					System.arraycopy(b, at, out, at + n, b.length - at);
					yield out;
				}
			};
		}

	}

}
