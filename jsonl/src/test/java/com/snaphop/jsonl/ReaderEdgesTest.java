package com.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/// Edges that mutation testing found untested.
class ReaderEdgesTest {

	static JsonReader stream(String json, int bufferSize) {
		return JsonReader.of(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), bufferSize);
	}

	static String error(String json, Consumer<JsonReader> read) {
		var e = assertThrows(JsonException.class, () -> {
			try (var r = JsonReader.of(json)) {
				read.accept(r);
			}
		});
		return String.valueOf(e.getMessage());
	}

	// Nesting: exactly MAX_DEPTH on every path

	static String nested(int n) {
		return "[".repeat(n) + "]".repeat(n);
	}

	@Test
	void nestsToTheLimitOnEveryPath() {
		int max = JsonReader.MAX_DEPTH;
		assertEquals(nested(max), Json.write(Json.read(nested(max))));
	}

	@Test
	void rejectsOneMoreThanTheLimitOnEveryPath() {
		int max = JsonReader.MAX_DEPTH;
		String ok = nested(max);
		String deep = nested(max + 1);
		List<Consumer<JsonReader>> paths = List.of(JsonReader::readValue, JsonReader::skipValue, JsonReader::readRaw,
				ReaderEdgesTest::pullAll, r -> {
					// Half by the pull API, the rest as a tree.
					for (int i = 0; i < max / 2; i++) {
						r.beginArray();
					}
					r.readValue();
				}, r -> {
					for (int i = 0; i < max / 2; i++) {
						r.beginArray();
					}
					r.skipValue();
				});
		for (var path : paths) {
			try (var r = JsonReader.of(ok)) {
				path.accept(r); // within the limit
			}
			assertTrue(error(deep, path).startsWith("JSON nests deeper than 1000 at "), () -> error(deep, path));
		}
		var w = JsonWriter.ofBytes();
		for (int i = 0; i < max; i++) {
			w.beginArray();
		}
		assertThrows(JsonException.class, w::beginArray);
		assertThrows(JsonException.class, w::beginObject);
	}

	static void pullAll(JsonReader r) {
		JsonReaderTest.pull(r);
	}

	// Errors through the pull API

	@Test
	void pullErrors() {
		assertEquals("Expected ',' or ']' but found '2' at line 1, column 4 (offset 3)", error("[1 2]", ReaderEdgesTest::pullAll));
		assertEquals("Expected ',' or '}' but found '\"' at line 1, column 8 (offset 7)",
				error("{\"a\":1 \"b\":2}", ReaderEdgesTest::pullAll));
		assertEquals("Expected a name but found '1' at line 1, column 2 (offset 1)", error("{1:2}", ReaderEdgesTest::pullAll));
		assertEquals("Expected a name but found '}' at line 1, column 8 (offset 7)", error("{\"a\":1,}", ReaderEdgesTest::pullAll));
		assertEquals("Expected ':' but found '1' at line 1, column 6 (offset 5)", error("{\"a\" 1}", ReaderEdgesTest::pullAll));
		assertEquals("Expected a value but found ']' at line 1, column 4 (offset 3)", error("[1,]", ReaderEdgesTest::pullAll));
		assertEquals("Expected a name but the input ended at line 1, column 2 (offset 1)", error("{", ReaderEdgesTest::pullAll));
		assertEquals("Expected '{' but found '[' at line 1, column 2 (offset 1)", error("[]", JsonReader::beginObject));
		assertEquals("Expected '[' but found the end of the input at line 1, column 3 (offset 2)", error("  ", JsonReader::beginArray));
		assertEquals("Expected a boolean but found null at line 1, column 5 (offset 4)", error("null", JsonReader::nextBoolean));
		assertEquals("Expected a boolean but found a number at line 1, column 1 (offset 0)", error("1", JsonReader::nextBoolean));
		assertEquals("Expected a string but found true at line 1, column 5 (offset 4)", error("true", JsonReader::nextString));
		assertEquals("Expected a number but found a string at line 1, column 2 (offset 1)", error("\"1\"", JsonReader::nextLong));
		assertEquals("Expected null but found false at line 1, column 6 (offset 5)", error("false", JsonReader::nextNull));
		assertEquals("Expected a name but found a string at line 1, column 2 (offset 1)", error("\"a\"", JsonReader::nextName));
		assertEquals("Expected a value but found ']' at line 1, column 3 (offset 2)", error("[]", r -> {
			r.beginArray();
			r.readValue();
		}));
		assertEquals("Expected a value but found '}' at line 1, column 3 (offset 2)", error("{}", r -> {
			r.beginObject();
			r.skipValue();
		}));
		assertEquals("Expected a value but found the end of the input at line 1, column 1 (offset 0)", error("", JsonReader::readRaw));
		assertEquals("Expected '}' but found a name at line 1, column 3 (offset 2)", error("{\"a\":1}", r -> {
			r.beginObject();
			r.endObject();
		}));
		assertEquals("Expected ']' but found a number at line 1, column 2 (offset 1)", error("[1]", r -> {
			r.beginArray();
			r.endArray();
		}));
	}

	@Test
	void namesBytes() {
		assertEquals("Expected a value but found byte 0x00 at line 1, column 4 (offset 3)", error("[1,\u0000]", JsonReader::readValue));
		assertEquals("Expected a value but found byte 0x01 at line 1, column 4 (offset 3)", error("[1,\u0001]", JsonReader::readValue));
		assertEquals("Expected a value but found byte 0x7F at line 1, column 2 (offset 1)", error("[\u007f]", JsonReader::readValue));
		assertEquals("Expected a value but found byte 0xC2 at line 1, column 2 (offset 1)", error("[\u00a0]", JsonReader::readValue));
		assertEquals("Expected a value but found '~' at line 1, column 2 (offset 1)", error("[~]", JsonReader::readValue));
	}

	@Test
	void peeksAndConsumes() {
		try (var r = JsonReader.of("[true,false,null]")) {
			r.beginArray();
			assertEquals(JsonReader.Token.BOOLEAN, r.peek());
			assertTrue(r.nextBoolean());
			assertFalse(r.nextBoolean());
			assertThrows(JsonException.class, r::nextBoolean);
			r.nextNull();
			r.endArray();
		}
		try (var r = JsonReader.of("[null,1,\"s\"]")) {
			r.beginArray();
			assertEquals(JsonReader.Token.NULL, r.peek());
			assertTrue(r.nextIfNull());
			assertFalse(r.nextIfNull());
			assertEquals(1, r.nextInt());
			assertFalse(r.nextIfNull());
			assertEquals("s", r.nextString());
			assertFalse(r.hasNext());
			assertEquals(JsonReader.Token.END_ARRAY, r.peek());
			r.endArray();
			assertEquals(JsonReader.Token.END, r.peek());
			assertFalse(r.hasNext());
		}
		try (var r = JsonReader.of("{\"a\":{}}")) {
			assertEquals(JsonReader.Token.BEGIN_OBJECT, r.peek());
			r.beginObject();
			assertEquals(JsonReader.Token.NAME, r.peek());
			r.nextName();
			assertEquals(JsonReader.Token.BEGIN_OBJECT, r.peek());
			r.beginObject();
			assertEquals(JsonReader.Token.END_OBJECT, r.peek());
			r.endObject();
			r.endObject();
		}
	}

	// Byte order mark, offsets, closing

	@Test
	void skipsAByteOrderMark() {
		byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '[', '1', ']' };
		assertEquals(List.of(1L), Json.read(bom));
		assertEquals(List.of(1L), Json.read(new JsonReaderTest.Trickle(bom, null)));
		for (byte[] bad : List.of(new byte[] { (byte) 0xEF, (byte) 0xBB, '1' }, new byte[] { (byte) 0xEF, 0, (byte) 0xBF, '1' },
				new byte[] { ' ', (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '1' })) {
			assertThrows(JsonException.class, () -> Json.read(bad));
		}
	}

	@Test
	void countsFromAnOffset() {
		byte[] bytes = "xxxxx[1,]".getBytes(StandardCharsets.US_ASCII);
		var e = assertThrows(JsonException.class, () -> {
			try (var r = JsonReader.of(bytes, 5, 4)) {
				r.readValue();
			}
		});
		assertEquals("Expected a value but found ']' at line 1, column 4 (offset 3)", e.getMessage());
		assertThrows(IndexOutOfBoundsException.class, () -> JsonReader.of(bytes, 5, 5));
		assertThrows(IllegalArgumentException.class, () -> JsonReader.of(InputStream.nullInputStream(), 15));
		try (var r = JsonReader.of(InputStream.nullInputStream(), 16)) {
			assertFalse(r.hasNext());
		}
	}

	@Test
	void closesTheStream() {
		var closed = new boolean[1];
		var in = new ByteArrayInputStream(new byte[0]) {
			@Override
			public void close() {
				closed[0] = true;
			}
		};
		JsonReader.of(in).close();
		assertTrue(closed[0]);
		JsonReader.of("1").close();
		var failing = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("disk on fire");
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				throw new IOException("disk on fire");
			}

			@Override
			public void close() throws IOException {
				throw new IOException("still on fire");
			}
		};
		var r = JsonReader.of(failing);
		assertEquals("Can't read JSON at line 1, column 1 (offset 0): disk on fire",
				assertThrows(JsonException.class, r::peek).getMessage());
		assertEquals("Can't close the JSON input: still on fire", assertThrows(JsonException.class, r::close).getMessage());
	}

	/// A stream that may return 0 bytes from a read, as some do.
	@Test
	void retriesEmptyReads() {
		byte[] bytes = "[\"abc\",123]".getBytes(StandardCharsets.US_ASCII);
		var in = new InputStream() {
			int i;

			boolean empty;

			@Override
			public int read() {
				return i < bytes.length ? bytes[i++] : -1;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				empty = !empty;
				if (empty) {
					return 0;
				}
				if (i == bytes.length) {
					return -1;
				}
				b[off] = bytes[i++];
				return 1;
			}
		};
		assertEquals(List.of("abc", 123L), Json.read(in));
	}

	// Strings

	@Test
	void decodesEscapesAtUtf8Boundaries() {
		String json = "\"\\u007f\\u0080\\u07ff\\u0800\\uffff\\ud800\\udc00\\udbff\\udfff\\u00e9\\u00E9\"";
		String expected = "\u007f\u0080\u07ff\u0800\uffff\ud800\udc00\udbff\udfff\u00e9\u00e9";
		assertEquals(expected, Json.read(json));
		assertEquals(expected, Json.read(new JsonReaderTest.Trickle(json.getBytes(StandardCharsets.US_ASCII), null)));
	}

	@Test
	void replacesAHighSurrogateWithoutItsLowOne() {
		assertEquals("\uFFFDabcdef", Json.read("\"\\ud83dabcdef\""));
		assertEquals("\uFFFD\\uzzzz", Json.read("\"\\ud83d\\\\uzzzz\""));
		assertEquals("\uFFFDx", Json.read("\"\\ud83dx\""));
		assertEquals("\uFFFD", Json.read("\"\\ud83d\""));
		assertEquals("\uFFFD\n", Json.read("\"\\ud83d\\n\""));
	}

	@Test
	void rejectsBadStrings() {
		assertEquals("Unescaped control character in a string at line 1, column 6 (offset 5)",
				error("\"a\\nb\u0001nc\"", JsonReader::readValue));
		assertEquals("Unescaped control character in a string at line 1, column 3 (offset 2)", error("\"a\u001f\"", JsonReader::readValue));
		assertEquals("Invalid escape '\\x' at line 1, column 4 (offset 3)", error("\"a\\x\"", JsonReader::readValue));
		assertEquals("Invalid escape '\\\u00c3' at line 1, column 3 (offset 2)", error("\"\\é\"", JsonReader::readValue));
		assertEquals("Invalid \\u escape at line 1, column 6 (offset 5)", error("\"\\u12g4\"", JsonReader::readValue));
		assertEquals("Unexpected end of input at line 1, column 4 (offset 3)", error("\"\\u12", JsonReader::readValue));
		assertEquals("Unterminated string at line 1, column 5 (offset 4)", error("\"abc", JsonReader::readValue));
		assertEquals("Unterminated string at line 1, column 6 (offset 5)", error("\"ab\\n", JsonReader::readValue));
		assertEquals("Unexpected end of input at line 1, column 5 (offset 4)", error("\"ab\\", JsonReader::readValue));
		// The same, skipped
		assertEquals("Unescaped control character in a string at line 1, column 6 (offset 5)",
				error("\"a\\nb\u0001nc\"", JsonReader::skipValue));
		assertEquals("Unterminated string at line 1, column 5 (offset 4)", error("\"abc", JsonReader::skipValue));
		assertEquals("Unexpected end of input at line 1, column 5 (offset 4)", error("\"ab\\", JsonReader::skipValue));
		assertEquals("Unterminated string at line 1, column 8 (offset 7)", error("[\"ab\\\"]", JsonReader::skipValue));
	}

	@Test
	void skipsStringsHoldingBrackets() {
		String json = "[{\"a\":\"]}\\\"]}\",\"b\":[\"[\",\"{\"]},\n\"\\\\\"]";
		for (int size = 16; size < 40; size++) {
			try (var r = stream(json + " 7", size)) {
				r.skipValue();
				assertEquals(7, r.nextLong());
				assertEquals(2, r.line());
			}
			try (var r = stream(json + " 7", size)) {
				assertEquals(json, r.readRaw());
			}
		}
		assertEquals("Unexpected end of input at line 2, column 3 (offset 6)", error("[[]\n,[", JsonReader::skipValue));
	}

	@Test
	void namesWithEqualHashes() {
		assertEquals("Aa".hashCode(), "BB".hashCode());
		for (int i = 0; i < 3; i++) {
			var map = Json.map(Json.read("{\"Aa\":1,\"BB\":2,\"AaAa\":3,\"BBBB\":4,\"AaBB\":5}"));
			assertEquals(List.of("Aa", "BB", "AaAa", "BBBB", "AaBB"), new ArrayList<>(map.keySet()));
		}
		String longName = "n".repeat(33);
		assertEquals(Map.of(longName, 1L, longName + "x", 2L), Json.read("{\"" + longName + "\":1,\"" + longName + "x\":2}"));
	}

	// Numbers

	@Test
	void readsNumberEdges() {
		assertEquals(new BigInteger("9223372036854775808"), Json.read("9223372036854775808"));
		assertEquals(new BigInteger("-9223372036854775809"), Json.read("-9223372036854775809"));
		assertEquals(Long.MAX_VALUE, Json.read("9223372036854775807"));
		assertEquals(Long.MIN_VALUE, Json.read("-9223372036854775808"));
		assertEquals(999_999_999_999_999_999L, Json.read("999999999999999999"));
		for (String d : List.of("0.0000000000000000000012345", "0.0000000000000000000000000000001",
				"-0.00000000000000000000000000001e10", "1e99999999999", "1e-99999999999", "-1E+400", "1e22", "1e23", "1e-22",
				"1e-23", "9007199254740993.0", "9007199254740992e1", "1.23e2", "5e-1", "-0.0", "0.000000000000000000001e+20",
				"123456789012345678.5", "1234567890123456789012e-3")) {
			assertEquals(Double.parseDouble(d), Json.read(d), d);
		}
		assertInstanceOf(Long.class, Json.read("-0"));
	}

	@Test
	void namesBadNumbers() {
		assertEquals("Invalid number '1.2.3' at line 1, column 2 (offset 1)", error("[1.2.3]", JsonReader::readValue));
		assertEquals("Invalid number '1-2' at line 1, column 2 (offset 1)", error("[1-2]", JsonReader::readValue));
		assertEquals("Invalid number '1e5e5' at line 1, column 2 (offset 1)", error("[1e5e5]", JsonReader::readValue));
		assertEquals("Invalid number '-' at line 1, column 2 (offset 1)", error("[-]", JsonReader::readValue));
		assertEquals("Invalid number '1.' at line 1, column 2 (offset 1)", error("[1.]", JsonReader::readValue));
		assertEquals("Invalid number '1e+' at line 1, column 2 (offset 1)", error("[1e+]", JsonReader::readValue));
		assertEquals("Invalid number '-01' at line 1, column 2 (offset 1)", error("[-01]", JsonReader::readValue));
		assertEquals("Number 1.5 isn't a long at line 1, column 1 (offset 0)", error("1.5", JsonReader::nextLong));
		assertEquals("Number 2147483648 isn't an int at line 1, column 1 (offset 0)", error("2147483648", JsonReader::nextInt));
		assertEquals("Number 1e99 isn't an int at line 1, column 1 (offset 0)", error("1e99", JsonReader::nextInt));
		try (var r = JsonReader.of("[-2147483648,2147483647]")) {
			r.beginArray();
			assertEquals(Integer.MIN_VALUE, r.nextInt());
			assertEquals(Integer.MAX_VALUE, r.nextInt());
		}
		// A number split at every place in a stream
		for (int size = 16; size < 48; size++) {
			try (var r = stream(" ".repeat(size - 5) + "[12345.678e-2,-0.5,98765432109876543210]", size)) {
				r.beginArray();
				assertEquals(123.45678, r.nextDouble());
				assertEquals("-0.5", r.nextString());
				assertEquals(new java.math.BigDecimal("98765432109876543210"), r.nextBigDecimal());
				r.endArray();
			}
		}
	}

	@Test
	void readsWholeNumbersSpelledAnyWay() {
		for (String n : List.of("9223372036854775807", "9223372036854775807.000", "922337203685477580.7e1",
				"92233720368547758070e-1", "0.9223372036854775807e19", "-9223372036854775808", "-9223372036854775808.0",
				"-922337203685477580.8E+1", "-0.0000000000000000009223372036854775808e37", "1e18", "100e-2", "0.5e1",
				"0e99999999999", "-0.000e-99999999999", "922337203685477580e1", "-922337203685477580e1", "1e18", "1" + "0".repeat(100) + "e-100", "5.0" + "0".repeat(50))) {
			try (var r = JsonReader.of(n)) {
				assertEquals(new java.math.BigDecimal(n.replaceAll("e-?9{11}", "")).longValueExact(), r.nextLong(), n);
			}
		}
		for (String n : List.of("9223372036854775808", "9223372036854775807.1", "922337203685477580.8e1",
				"-9223372036854775809", "-922337203685477580.9e1", "1e19", "0.5", "1e-1", "1.00000000000000000001",
				"123456789012345678.5", "1234567890123456789.5", "9999999999999999999", "-9999999999999999999", "99999999999999999.99e2", "1" + "0".repeat(39), "1e99999999999", "1E-99999999999", "-1e2147483648")) {
			assertEquals("Number " + n + " isn't a long at line 1, column 1 (offset 0)", error(n, JsonReader::nextLong));
		}
	}

	@Test
	void refusesExponentsPastABigDecimal() {
		assertEquals("Number 1e99999999999 is past a BigDecimal's range at line 1, column 1 (offset 0)",
				error("1e99999999999", JsonReader::nextBigDecimal));
		assertEquals("Number -1.5E-99999999999 is past a BigDecimal's range at line 1, column 1 (offset 0)",
				error("-1.5E-99999999999", JsonReader::nextBigDecimal));
		assertEquals("Number 1e99999999999 isn't an int at line 1, column 1 (offset 0)",
				error("1e99999999999", JsonReader::nextInt));
		try (var r = JsonReader.of("[0e99999999999,1e99999999999]")) {
			r.beginArray();
			assertEquals(java.math.BigDecimal.ZERO, r.nextBigDecimal());
			assertThrows(JsonException.class, r::nextBigDecimal);
			assertEquals("1e99999999999", r.nextString()); // still next after the failure
			r.endArray();
		}
	}

	@Test
	void longNumbersAreQuickToRefuseAndShortInMessages() {
		String digits = "9".repeat(1_000_000);
		assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
			assertEquals("Number " + "9".repeat(40) + "... (1000000 characters) isn't a long"
					+ " at line 1, column 1 (offset 0)", error(digits, JsonReader::nextLong));
			assertEquals("Number 1." + "0".repeat(38) + "... (1000002 characters) isn't an int"
					+ " at line 1, column 1 (offset 0)", error("1." + "0".repeat(999_999) + "1", JsonReader::nextInt));
			try (var r = JsonReader.of("1" + "0".repeat(999_999) + "e-999999")) {
				assertEquals(1L, r.nextLong());
			}
		});
		assertEquals("Invalid number '-" + "0".repeat(39) + "... (1000001 characters)'"
				+ " at line 1, column 1 (offset 0)", error("-" + "0".repeat(1_000_000), JsonReader::readValue));
	}

	@Test
	void limitsNumberLengthWhenAsked() {
		String digits = "9".repeat(1_000_000);
		for (Consumer<JsonReader> read : List.<Consumer<JsonReader>>of(JsonReader::readValue, JsonReader::nextBigDecimal,
				JsonReader::nextLong, JsonReader::nextInt, JsonReader::nextDouble, JsonReader::nextString)) {
			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertEquals("Number " + "9".repeat(40)
					+ "... is longer than the limit of 1000 characters at line 1, column 1 (offset 0)",
					error(digits, r -> read.accept(r.maxNumberLength(1000)))));
		}
		// The sign, point and exponent count; exactly the limit is read.
		try (var r = JsonReader.of("[-1.5e+10,-1.5e+100,12345]").maxNumberLength(8)) {
			r.beginArray();
			assertEquals(-1.5e10, r.nextDouble());
			var e = assertThrows(JsonException.class, r::readValue);
			assertEquals("Number -1.5e+10... is longer than the limit of 8 characters at line 1, column 11 (offset 10)",
					e.getMessage());
			r.skipValue(); // still next; skipping doesn't check it
			assertEquals(12345L, r.maxNumberLength(4).maxNumberLength(5).nextLong());
			r.endArray();
		}
		// A stream, the number split across fills
		try (var r = stream("[" + "1".repeat(100) + "]", 16).maxNumberLength(99)) {
			r.beginArray();
			assertThrows(JsonException.class, r::nextBigDecimal);
			assertEquals("1".repeat(100), r.readRaw()); // readRaw doesn't check it either
		}
		// Unlimited unless asked
		try (var r = JsonReader.of("1".repeat(10_000))) {
			assertEquals(new BigInteger("1".repeat(10_000)), r.readValue());
		}
		var e = assertThrows(IllegalArgumentException.class, () -> JsonReader.of("1").maxNumberLength(0));
		assertEquals("maxNumberLength 0 is under 1", e.getMessage());
		try (var r = JsonReader.of("[1,22]").maxNumberLength(1)) {
			r.beginArray();
			assertEquals(1, r.nextInt());
			assertThrows(JsonException.class, r::nextInt);
		}
	}

	/// A stream that's all digits, or all of one string, for as long as it's read.
	static InputStream endless(String start, byte fill) {
		return new InputStream() {
			int at;

			@Override
			public int read() {
				return at < start.length() ? start.charAt(at++) : fill;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				for (int i = 0; i < len; i++) {
					b[off + i] = (byte) read();
				}
				return len;
			}
		};
	}

	@Test
	void limitsStopReadingAnEndlessStream() {
		try (var r = JsonReader.of(endless("[", (byte) '7')).maxNumberLength(100_000)) {
			r.beginArray();
			assertEquals("Number " + "7".repeat(40) + "... is longer than the limit of 100000 characters at line 1, column 2"
					+ " (offset 1)", assertThrows(JsonException.class, r::readValue).getMessage());
			assertEquals(JsonReader.Token.NUMBER, r.peek()); // still next
		}
		try (var r = JsonReader.of(endless("[\"", (byte) 's')).maxStringLength(100_000)) {
			r.beginArray();
			assertEquals("A string is longer than the limit of 100000 bytes at line 1, column 3 (offset 2)",
					assertThrows(JsonException.class, r::nextString).getMessage());
		}
		try (var r = JsonReader.of(endless("{\"", (byte) 'n')).maxStringLength(100_000)) {
			r.beginObject();
			assertThrows(JsonException.class, r::nextName);
		}
		// A bad number stops at the limit too
		try (var r = JsonReader.of(endless("[1.2.", (byte) '7')).maxNumberLength(100_000)) {
			r.beginArray();
			assertEquals("Number 1.2." + "7".repeat(36) + "... is longer than the limit of 100000 characters"
					+ " at line 1, column 2 (offset 1)", assertThrows(JsonException.class, r::readValue).getMessage());
		}
	}

	@Test
	void limitsNumbersSplitAcrossFills() {
		String exact = "12345678901234567890";
		for (int pad = 0; pad < 40; pad++) {
			try (var r = stream(" ".repeat(pad) + "[" + exact + "," + exact + "1]", 16).maxNumberLength(20)) {
				r.beginArray();
				assertEquals(new BigInteger(exact), r.readValue(), "pad " + pad);
				assertThrows(JsonException.class, r::readValue, "pad " + pad);
			}
		}
		// A byte at a time, so a read ends at every place in the number
		byte[] json = ("[" + exact + "1," + exact + "]").getBytes(StandardCharsets.US_ASCII);
		var in = new ByteArrayInputStream(json) {
			@Override
			public synchronized int read(byte[] b, int off, int len) {
				return super.read(b, off, Math.min(len, 1));
			}
		};
		try (var r = JsonReader.of(in, 16).maxNumberLength(20)) {
			r.beginArray();
			assertThrows(JsonException.class, r::readValue);
			r.skipValue();
			assertEquals(new BigInteger(exact), r.readValue());
		}
		// A bad number at the limit is named; past it, it's too long
		assertEquals("Invalid number '1.2.3' at line 1, column 2 (offset 1)",
				error("[1.2.3]", r -> r.maxNumberLength(5).readValue()));
		assertEquals("Number 1.2.3... is longer than the limit of 5 characters at line 1, column 2 (offset 1)",
				error("[1.2.34]", r -> r.maxNumberLength(5).readValue()));
	}

	@Test
	void limitsStringLengthWhenAsked() {
		// UTF-8 bytes once unescaped: "é" is 2, "\\u00e9" is 2, "\\n" is 1.
		for (String s : List.of("\"abcd\"", "\"ab\u00e9\"", "\"abé\"", "\"a\\n\\u00e9\"", "\"\\n\\n\\n\\n\"", "\"\"")) {
			try (var r = JsonReader.of("[" + s + "," + s + "]").maxStringLength(4)) {
				r.beginArray();
				assertEquals(Json.read(s), r.nextString(), s);
				assertEquals(Json.read(s), r.readValue(), s);
			}
		}
		for (String s : List.of("\"abcde\"", "\"abc\u00e9\"", "\"abcé\"", "\"a\\n\\n\\u00e9\"", "\"\\n\\n\\n\\n\\n\"",
				"\"\\ud83d\\ude00a\"", "\"abcd\\n\"")) {
			var e = assertThrows(JsonException.class, () -> {
				try (var r = JsonReader.of(s).maxStringLength(4)) {
					r.nextString();
				}
			}, s);
			assertTrue(String.valueOf(e.getMessage()).startsWith("A string is longer than the limit of 4 bytes at line 1, column "), e.getMessage());
		}
		assertEquals("A name is longer than the limit of 2 bytes at line 1, column 3 (offset 2)",
				error("{\"abc\":1}", r -> Json.map(r.maxStringLength(2).readValue())));
		assertEquals("A string is longer than the limit of 2 bytes at line 1, column 9 (offset 8)",
				error("{\"ab\":[\"abc\"]}", r -> r.maxStringLength(2).readValue()));
		// Skipping and raw values don't check
		try (var r = JsonReader.of("[\"abcdef\",\"abcdef\",\"ab\"]").maxStringLength(2)) {
			r.beginArray();
			r.skipValue();
			assertEquals("\"abcdef\"", r.readRaw());
			assertEquals("ab", r.nextString());
		}
		// A stream, the string split across fills, escaped and not
		for (String s : List.of("x".repeat(100), "x".repeat(50) + "\\n" + "x".repeat(49))) {
			try (var r = stream("[\"" + s + "\"]", 16).maxStringLength(100)) {
				r.beginArray();
				assertEquals(100, r.nextString().length());
			}
			try (var r = stream("[\"" + s + "x\"]", 16).maxStringLength(100)) {
				r.beginArray();
				assertThrows(JsonException.class, r::nextString);
			}
		}
		var e = assertThrows(IllegalArgumentException.class, () -> JsonReader.of("1").maxStringLength(0));
		assertEquals("maxStringLength 0 is under 1", e.getMessage());
		try (var r = JsonReader.of("\"a\"").maxStringLength(1)) {
			assertEquals("a", r.nextString());
		}
	}

	@Test
	void treesAreMutable() {
		var map = Json.map(Json.read("{}"));
		map.put("a", 1L);
		var list = Json.list(Json.read("[]"));
		list.add(1L);
		assertEquals(List.of(), Json.list("not a list"));
		assertEquals(List.of(1L), Json.list(List.of(1L)));
		assertEquals(Map.of(), Json.map(List.of()));
		assertEquals(Map.of(), Json.map(null));
		assertEquals(List.of(), Json.list(null));
	}

	@Test
	void readsLiteralsAcrossBufferEdges() {
		for (int size = 16; size < 40; size++) {
			String json = " ".repeat(size - 3) + "[true,false,null]";
			assertEquals(java.util.Arrays.asList(true, false, null), readTree(stream(json, size)));
		}
		assertEquals("Expected true at line 1, column 15 (offset 14)",
				assertThrows(JsonException.class, () -> readTree(stream(" ".repeat(13) + "[tru", 16))).getMessage());
		assertThrows(JsonException.class, () -> readTree(stream(" ".repeat(13) + "[nul]", 16)));
		assertThrows(JsonException.class, () -> readTree(stream(" ".repeat(13) + "[fals", 16)));
	}

	static @Nullable Object readTree(JsonReader r) {
		return JsonReaderTest.readTree(r);
	}

}
