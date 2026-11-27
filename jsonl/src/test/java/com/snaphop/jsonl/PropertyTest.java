package com.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.snaphop.jsonl.FuzzTest.Chunks;
import com.snaphop.jsonl.FuzzTest.Gen;

/// Properties of random documents ([FuzzTest.Gen]) that hold whatever the document:
/// indenting changes only whitespace, JSON lines read back as the values written,
/// the limits refuse exactly what is past them, and an error's line and column are
/// where its offset is.
///
/// `-Dfuzz.iterations=N` sets how many of each (default 3,000) and `-Dfuzz.seed=S`
/// the first seed; a failure names the seed that reproduces it.
class PropertyTest {

	static final int ITERATIONS = FuzzTest.ITERATIONS;
	static final long SEED = FuzzTest.SEED;

	/// Indented, a value reads back as itself, and is its compact form once the
	/// whitespace outside strings is taken out.
	@Test
	void indentingChangesOnlyWhitespace() {
		String[] indents = { " ", "  ", "\t", "    " };
		for (int i = 0; i < ITERATIONS; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			Object tree = Gen.value(r, 0);
			String indent = indents[r.nextInt(indents.length)];
			String indented = JsonWriter.ofBytes().indent(indent).value(tree).toString();
			String compact = Json.write(tree);
			try {
				assertEquals(compact, outsideStrings(indented, false));
				FuzzTest.assertSame(tree, Json.read(indented), "indented, read back");
				assertEquals(indented.contains("\n"), compact.length() != indented.length(), "only indenting adds lines");
			}
			catch (AssertionError e) {
				throw new AssertionError("seed " + seed + ": " + e.getMessage() + "\nin " + FuzzTest.abbreviate(indented), e);
			}
		}
	}

	/// Values written one after another are one line each, each line the value's
	/// compact JSON, and read back in order from bytes and from a stream in random
	/// chunks; so are the same values formatted at random, one per line.
	@Test
	void jsonLines() {
		for (int i = 0; i < ITERATIONS; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			List<@Nullable Object> values = new ArrayList<>();
			for (int n = r.nextInt(6); n > 0; n--) {
				values.add(Gen.value(r, 2));
			}
			JsonWriter w = JsonWriter.ofBytes();
			var formatted = new StringBuilder();
			for (Object v : values) {
				w.value(v);
				formatted.append(Gen.format(r, v)).append('\n');
			}
			byte[] lines = w.toBytes();
			try {
				String text = new String(lines, StandardCharsets.UTF_8);
				List<String> split = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
				assertEquals(values.size(), split.size(), "lines");
				for (int k = 0; k < values.size(); k++) {
					assertEquals(Json.write(values.get(k)), split.get(k), "line " + (k + 1));
				}
				assertSequence(values, JsonReader.of(lines), "bytes");
				assertSequence(values, JsonReader.of(new Chunks(lines, r), 16 + r.nextInt(100)), "stream");
				byte[] f = formatted.toString().getBytes(StandardCharsets.UTF_8);
				assertSequence(values, JsonReader.of(new Chunks(f, r), 16 + r.nextInt(100)), "formatted");
			}
			catch (AssertionError e) {
				throw new AssertionError("seed " + seed + ": " + e.getMessage() + "\nin " + FuzzTest.abbreviate(formatted.toString()), e);
			}
		}
	}

	static void assertSequence(List<@Nullable Object> values, JsonReader reader, String context) {
		try (var r = reader) {
			for (int k = 0; k < values.size(); k++) {
				assertTrue(r.hasNext(), context + ": ended before value " + k);
				FuzzTest.assertSame(values.get(k), r.readValue(), context + " value " + k);
			}
			assertEquals(false, r.hasNext(), context + ": more values than written");
		}
	}

	/// `maxStringLength(n)` refuses a document exactly when a string or name is longer
	/// than `n` bytes of UTF-8 once unescaped, and `maxNumberLength(n)` exactly when a
	/// number is written in more than `n` characters.
	@Test
	void limitsRefuseExactlyWhatIsPastThem() {
		for (int i = 0; i < ITERATIONS; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			Object tree = Gen.value(r, 0);
			String json = Gen.format(r, tree);
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			int longestString = longestString(Json.read(bytes));
			int longestNumber = 0;
			for (String n : numbers(json)) {
				longestNumber = Math.max(longestNumber, n.length());
			}
			try {
				for (int limit : limits(r, longestString)) {
					boolean refused = refused(() -> JsonReader.of(new Chunks(bytes, r), 16 + r.nextInt(64)).maxStringLength(limit).readValue());
					assertEquals(longestString > limit, refused, "maxStringLength " + limit + ", longest " + longestString);
				}
				for (int limit : limits(r, longestNumber)) {
					boolean refused = refused(() -> JsonReader.of(new Chunks(bytes, r), 16 + r.nextInt(64)).maxNumberLength(limit).readValue());
					assertEquals(longestNumber > limit, refused, "maxNumberLength " + limit + ", longest " + longestNumber);
				}
			}
			catch (AssertionError e) {
				throw new AssertionError("seed " + seed + ": " + e.getMessage() + "\nin " + FuzzTest.abbreviate(json), e);
			}
		}
	}

	/// Limits at and either side of the longest, and one at random.
	static int[] limits(Random r, int longest) {
		return new int[] { Math.max(1, longest - 1), Math.max(1, longest), longest + 1, 1 + r.nextInt(Math.max(1, longest * 2)) };
	}

	static boolean refused(Runnable read) {
		try {
			read.run();
			return false;
		}
		catch (JsonException e) {
			assertTrue(String.valueOf(e.getMessage()).contains("is longer than the limit of"), () -> "Refused for another reason: " + e.getMessage());
			return true;
		}
	}

	/// The longest string or name, in bytes of UTF-8.
	static int longestString(@Nullable Object v) {
		return switch (v) {
			case String s -> s.getBytes(StandardCharsets.UTF_8).length;
			case Map<?, ?> m -> {
				int most = 0;
				for (var e : m.entrySet()) {
					most = Math.max(most, Math.max(longestString(e.getKey()), longestString(e.getValue())));
				}
				yield most;
			}
			case List<?> l -> l.stream().mapToInt(PropertyTest::longestString).max().orElse(0);
			case null, default -> 0;
		};
	}

	static final Pattern NUMBER = Pattern.compile("-?[0-9][0-9.eE+-]*");

	/// The numbers in JSON text, as written.
	static List<String> numbers(String json) {
		List<String> out = new ArrayList<>();
		Matcher m = NUMBER.matcher(outsideStrings(json, true));
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	/// The JSON text without its whitespace outside strings or, if `blankStrings`,
	/// with each string's content replaced by nothing, keeping whitespace.
	static String outsideStrings(String json, boolean blankStrings) {
		var sb = new StringBuilder();
		boolean in = false;
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (in) {
				if (c == '\\') {
					if (!blankStrings) {
						sb.append(c).append(json.charAt(i + 1));
					}
					i++;
					continue;
				}
				if (c == '"') {
					in = false;
					sb.append(c);
				}
				else if (!blankStrings) {
					sb.append(c);
				}
			}
			else if (c == '"') {
				in = true;
				sb.append(c);
			}
			else if (blankStrings || " \t\n\r".indexOf(c) < 0) {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	static final Pattern LOCATION = Pattern.compile("line (\\d+), column (\\d+) \\(offset (\\d+)\\)");

	/// Where an error says it is agrees with the bytes: its offset is in the input,
	/// its line is one more than the line feeds before the offset, and its column
	/// counts bytes from the last of them (or from after a byte order mark).
	@Test
	void errorLocationsAgreeWithTheirOffsets() {
		int located = 0;
		for (int i = 0; i < ITERATIONS * 4; i++) {
			long seed = SEED + i;
			var r = new Random(seed);
			byte[] damaged = Gen.format(r, Gen.value(r, 0)).getBytes(StandardCharsets.UTF_8);
			for (int m = 1 + r.nextInt(3); m > 0; m--) {
				damaged = Gen.damage(r, damaged);
			}
			byte[] bytes = damaged;
			for (int way = 0; way < 2; way++) {
				String message;
				try {
					if (way == 0) {
						Json.read(bytes);
					}
					else {
						FuzzTest.pullOne(JsonReader.of(new Chunks(bytes, r), 16));
					}
					continue;
				}
				catch (JsonException e) {
					message = String.valueOf(e.getMessage());
				}
				Matcher m = LOCATION.matcher(message);
				if (!m.find()) {
					fail("seed " + seed + ": no location in " + message);
				}
				long line = Long.parseLong(m.group(1));
				long column = Long.parseLong(m.group(2));
				int offset = Integer.parseInt(m.group(3));
				if (offset > bytes.length) {
					fail("seed " + seed + ": offset " + offset + " past the " + bytes.length + " bytes: " + message);
				}
				long lines = 1;
				// A byte order mark is not a column.
				int lineStart = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
				for (int p = 0; p < offset; p++) {
					if (bytes[p] == '\n') {
						lines++;
						lineStart = p + 1;
					}
				}
				String where = "seed " + seed + (way == 0 ? " read" : " pulled") + ": " + message + "\nin "
						+ FuzzTest.abbreviate(new String(bytes, StandardCharsets.UTF_8));
				assertEquals(lines, line, where);
				assertEquals(offset - lineStart + 1, column, where);
				located++;
			}
		}
		assertTrue(located > ITERATIONS, "located " + located);
	}
}
