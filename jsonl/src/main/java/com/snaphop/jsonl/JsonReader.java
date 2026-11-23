package com.snaphop.jsonl;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/// Pulls JSON from bytes or a stream, one token at a time. UTF-8 only. Not thread safe.
///
/// The input may hold any number of values one after another, separated by whitespace,
/// as JSON lines does:
///
/// ```
/// try (var r = JsonReader.of(in)) {
///     while (r.hasNext()) {
///         Object line = r.readValue();
///     }
/// }
/// ```
///
/// Nothing is limited but nesting ([#MAX_DEPTH]) unless asked: a string or a raw value
/// may be as large as memory allows. For JSON nobody vouches for, set
/// [#maxNumberLength(int)] and [#maxStringLength(int)], and bound the input as a whole.
/// The buffer grows to hold a long token when needed, and a token may start and end
/// anywhere in the stream.
///
/// Invalid UTF-8 inside strings becomes U+FFFD, as [String#String(byte[],
/// java.nio.charset.Charset)] does, and so does an escaped lone surrogate.
public final class JsonReader implements AutoCloseable {

	/// What [#peek()] finds next.
	public enum Token {
		/// `{`
		BEGIN_OBJECT,
		/// `}`
		END_OBJECT,
		/// `[`
		BEGIN_ARRAY,
		/// `]`
		END_ARRAY,
		/// A member's name.
		NAME,
		/// A string value.
		STRING,
		/// A number.
		NUMBER,
		/// `true` or `false`.
		BOOLEAN,
		/// `null`.
		NULL,
		/// The end of the input.
		END
	}

	/// How deeply arrays and objects may nest.
	public static final int MAX_DEPTH = 1000;

	/// The default size of the buffer for a stream.
	public static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

	private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 16;

	private static final byte[] EMPTY = new byte[0];

	// Scopes: where in the structure the reader is.
	private static final int DOCUMENT = 0;

	private static final int ARRAY_EMPTY = 1;

	private static final int ARRAY = 2;

	private static final int OBJECT_EMPTY = 3;

	private static final int OBJECT = 4;

	/// After a name and its colon, before the value.
	private static final int OBJECT_VALUE = 5;

	// What peek found, with its first byte consumed (all but numbers).
	private static final int P_NONE = 0;

	private static final int P_BEGIN_OBJECT = 1;

	private static final int P_END_OBJECT = 2;

	private static final int P_BEGIN_ARRAY = 3;

	private static final int P_END_ARRAY = 4;

	private static final int P_NAME = 5;

	private static final int P_STRING = 6;

	private static final int P_NUMBER = 7;

	private static final int P_TRUE = 8;

	private static final int P_FALSE = 9;

	private static final int P_NULL = 10;

	private static final int P_END = 11;

	private final @Nullable InputStream in;

	private byte[] buf;

	private int pos;

	private int limit;

	/// The start of a raw value being read, kept by [#fill()], or -1.
	private int mark = -1;

	private boolean eof;

	/// How many bytes of input came before `buf[0]`.
	private long discarded;

	private long line = 1;

	/// The offset of the first byte of the current line.
	private long lineStart;

	private int[] stack = new int[32];

	/// The scopes on [#stack], the document's included: one more than the nesting.
	private int depth = 1;

	private int peeked = P_NONE;

	/// Unescaped string bytes.
	private byte[] scratch = EMPTY;

	// The number scanNumber last read.
	private boolean numNegative;

	private long numMantissa;

	private long numExponent;

	private boolean numIntegral;

	/// Nonzero significant digits past what numMantissa holds. An integer's dropped
	/// digits count in numExponent too, so an integer fits a long only when that's 0.
	private boolean numTruncated;

	private int maxNumberLength = Integer.MAX_VALUE;

	private int maxStringLength = Integer.MAX_VALUE;

	private JsonReader(@Nullable InputStream in, byte[] buf, int pos, int limit) {
		this.in = in;
		this.buf = buf;
		this.pos = pos;
		this.limit = limit;
		this.eof = in == null;
		this.discarded = -pos;
		this.lineStart = 0;
		this.stack[0] = DOCUMENT;
	}

	/// Reads JSON from bytes, which are not copied and must not change while reading.
	/// @param json UTF-8 JSON.
	/// @return a reader.
	public static JsonReader of(byte[] json) {
		return new JsonReader(null, json, 0, json.length);
	}

	/// Reads JSON from part of an array, which is not copied and must not change while
	/// reading.
	/// @param json holds UTF-8 JSON.
	/// @param offset where the JSON starts.
	/// @param length how many bytes it has.
	/// @return a reader.
	public static JsonReader of(byte[] json, int offset, int length) {
		java.util.Objects.checkFromIndexSize(offset, length, json.length);
		return new JsonReader(null, json, offset, offset + length);
	}

	/// Reads JSON from a string.
	/// @param json the JSON.
	/// @return a reader.
	public static JsonReader of(String json) {
		return of(json.getBytes(StandardCharsets.UTF_8));
	}

	/// Reads JSON from a stream, which [#close()] closes.
	/// @param in UTF-8 JSON.
	/// @return a reader.
	public static JsonReader of(InputStream in) {
		return of(in, DEFAULT_BUFFER_SIZE);
	}

	/// Reads JSON from a stream, which [#close()] closes.
	/// @param in UTF-8 JSON.
	/// @param bufferSize the starting size of the buffer, at least 16.
	/// @return a reader.
	public static JsonReader of(InputStream in, int bufferSize) {
		if (bufferSize < 16) {
			throw new IllegalArgumentException("bufferSize " + bufferSize + " is under 16");
		}
		return new JsonReader(in, new byte[bufferSize], 0, 0);
	}

	/// Refuses, from now on, to read a number longer than `length` characters (its sign,
	/// point and exponent included), with a [JsonException]. The check can read ahead
	/// into the stream buffer. A pull read leaves the refused number next. There is no
	/// limit unless this is called.
	///
	/// For JSON nobody vouches for, as is [#maxStringLength(int)]. The JDK's `BigInteger`
	/// and `BigDecimal` take time that grows with the square of a number's length: about
	/// 12 seconds for a million digits. [#readValue()] reads a whole number past a `long`
	/// with `BigInteger`, and [#nextBigDecimal()] reads any number with `BigDecimal`.
	/// 1000, as Jackson allows, is plenty for any real number. [#skipValue()] and
	/// [#readRaw()] don't read the numbers they pass over, so they don't check this.
	///
	/// ```
	/// try (var r = JsonReader.of(request).maxNumberLength(1000).maxStringLength(1_000_000)) {
	///     Object body = r.readValue();
	/// }
	/// ```
	/// @param length the most characters a number may have, at least 1.
	/// @return this reader.
	public JsonReader maxNumberLength(int length) {
		if (length < 1) {
			throw new IllegalArgumentException("maxNumberLength " + length + " is under 1");
		}
		this.maxNumberLength = length;
		return this;
	}

	/// Refuses, from now on, to read a string or a name longer than `length` bytes of
	/// UTF-8 once unescaped, with a [JsonException]. The check can read ahead into the
	/// stream buffer. Close the reader after this error. There is no limit unless this
	/// is called.
	///
	/// For JSON nobody vouches for, from a stream with no end that bounds it, such as
	/// JSON lines: without it, one string can take all the memory there is. A limit on
	/// the input as a whole (an HTTP server's on a request body) bounds strings too.
	/// [#skipValue()] and [#readRaw()] don't read the strings they pass over, so they
	/// don't check this. Skipping needs no memory; [#readRaw()] keeps the whole value.
	/// @param length the most bytes a string or name may have, at least 1.
	/// @return this reader.
	public JsonReader maxStringLength(int length) {
		if (length < 1) {
			throw new IllegalArgumentException("maxStringLength " + length + " is under 1");
		}
		this.maxStringLength = length;
		return this;
	}

	// Structure

	/// What comes next, without consuming it.
	/// @return the next token.
	public Token peek() {
		return switch (peekCode()) {
			case P_BEGIN_OBJECT -> Token.BEGIN_OBJECT;
			case P_END_OBJECT -> Token.END_OBJECT;
			case P_BEGIN_ARRAY -> Token.BEGIN_ARRAY;
			case P_END_ARRAY -> Token.END_ARRAY;
			case P_NAME -> Token.NAME;
			case P_STRING -> Token.STRING;
			case P_NUMBER -> Token.NUMBER;
			case P_TRUE, P_FALSE -> Token.BOOLEAN;
			case P_NULL -> Token.NULL;
			default -> Token.END;
		};
	}

	/// Whether the current array or object has another element or member, or, outside
	/// any, whether the input has another value.
	/// @return false at `]`, `}` or the end of the input.
	public boolean hasNext() {
		int p = peekCode();
		return p != P_END_OBJECT && p != P_END_ARRAY && p != P_END;
	}

	/// Consumes `{`.
	public void beginObject() {
		expect(P_BEGIN_OBJECT, "'{'");
		push(OBJECT_EMPTY);
	}

	/// Consumes `}`.
	public void endObject() {
		expect(P_END_OBJECT, "'}'");
		depth--;
	}

	/// Consumes `[`.
	public void beginArray() {
		expect(P_BEGIN_ARRAY, "'['");
		push(ARRAY_EMPTY);
	}

	/// Consumes `]`.
	public void endArray() {
		expect(P_END_ARRAY, "']'");
		depth--;
	}

	/// Reads a member's name and its colon.
	/// @return the name.
	public String nextName() {
		expect(P_NAME, "a name");
		String name = readString(true);
		colon();
		return name;
	}

	// Values

	/// Reads a string, or a number as it was written.
	/// @return the string.
	public String nextString() {
		int p = peekCode();
		if (p == P_STRING) {
			peeked = P_NONE;
			return readString(false);
		}
		if (p == P_NUMBER) {
			peeked = P_NONE;
			int end = number();
			int start = pos;
			pos = end;
			return new String(buf, start, end - start, StandardCharsets.ISO_8859_1);
		}
		throw unexpectedToken(p, "a string");
	}

	/// Reads a number that has no fraction.
	/// @return the number.
	public long nextLong() {
		expect(P_NUMBER, "a number");
		return readLong(false);
	}

	/// Reads a number that has no fraction and fits an `int`.
	/// @return the number.
	public int nextInt() {
		expect(P_NUMBER, "a number");
		return (int) readLong(true);
	}

	/// Reads a number.
	/// @return the nearest double.
	public double nextDouble() {
		expect(P_NUMBER, "a number");
		int end = number();
		int start = pos;
		pos = end;
		return toDouble(start, end);
	}

	/// Reads a number exactly.
	/// @return the number.
	public BigDecimal nextBigDecimal() {
		expect(P_NUMBER, "a number");
		int end = number();
		int start = pos;
		BigDecimal d;
		try {
			d = new BigDecimal(new String(buf, start, end - start, StandardCharsets.ISO_8859_1));
		}
		catch (NumberFormatException e) {
			// The scanner already checked the syntax. BigDecimal limits exponent and scale.
			if (numMantissa != 0) {
				peeked = P_NUMBER;
				throw error("Number " + numberText(start, end) + " is past a BigDecimal's range");
			}
			d = BigDecimal.ZERO;
		}
		pos = end;
		return d;
	}

	/// Reads `true` or `false`.
	/// @return the boolean.
	public boolean nextBoolean() {
		int p = peekCode();
		if (p == P_TRUE || p == P_FALSE) {
			peeked = P_NONE;
			return p == P_TRUE;
		}
		throw unexpectedToken(p, "a boolean");
	}

	/// Reads `null`.
	public void nextNull() {
		expect(P_NULL, "null");
	}

	/// Reads `null` if it's next.
	/// @return whether it was.
	public boolean nextIfNull() {
		if (peekCode() == P_NULL) {
			peeked = P_NONE;
			return true;
		}
		return false;
	}

	/// Reads the next value as maps (in order), lists, strings, `Long`s (`BigInteger`s
	/// past a `long`), `Double`s, booleans and nulls.
	/// @return the value.
	public @Nullable Object readValue() {
		int p = peekCode();
		peeked = P_NONE;
		return switch (p) {
			case P_BEGIN_OBJECT -> readObject(depth);
			case P_BEGIN_ARRAY -> readArray(depth);
			case P_STRING -> readString(false);
			case P_NUMBER -> readNumber();
			case P_TRUE -> true;
			case P_FALSE -> false;
			case P_NULL -> null;
			default -> {
				peeked = p;
				throw unexpectedToken(p, "a value");
			}
		};
	}

	/// Reads the next value as the JSON it was written as. Checks matching brackets
	/// and string escapes. Number tokens and other syntax inside containers pass
	/// through as written.
	/// @return the JSON.
	public String readRaw() {
		int start = rawStart();
		if (start < 0) {
			return literal(-start);
		}
		String raw = new String(buf, mark, pos - mark, StandardCharsets.UTF_8);
		mark = -1;
		return raw;
	}

	/// Reads the next value as the UTF-8 JSON it was written as, as [#readRaw()] does.
	/// @return the JSON.
	public byte[] readRawBytes() {
		int start = rawStart();
		if (start < 0) {
			return literal(-start).getBytes(StandardCharsets.US_ASCII);
		}
		byte[] raw = Arrays.copyOfRange(buf, mark, pos);
		mark = -1;
		return raw;
	}

	/// Skips the next value, or a name and its value, with bounded buffering.
	/// Checks matching brackets and string escapes while passing over number tokens
	/// and the remaining container grammar.
	public void skipValue() {
		int p = peekCode();
		peeked = P_NONE;
		switch (p) {
			case P_BEGIN_OBJECT, P_BEGIN_ARRAY -> skipContainer(p);
			case P_NAME -> {
				skipString();
				colon();
				skipValue();
			}
			case P_STRING -> skipString();
			case P_NUMBER -> skipNumber();
			case P_TRUE, P_FALSE, P_NULL -> {
			}
			default -> {
				peeked = p;
				throw unexpectedToken(p, "a value");
			}
		}
	}

	/// Whether the input has nothing but whitespace left. Called after a whole value
	/// that was outside any array or object.
	boolean atEnd() {
		return nextNonWhitespace() < 0;
	}

	// Where

	/// How many bytes have been consumed.
	/// @return the offset of the next byte.
	public long position() {
		return discarded + pos;
	}

	/// The line of the next byte, from 1.
	/// @return the line.
	public long line() {
		return line;
	}

	/// The column of the next byte, in bytes, from 1.
	/// @return the column.
	public long column() {
		return position() - lineStart + 1;
	}

	/// Where the reader is, for messages.
	/// @return the line, column and offset.
	public String location() {
		return "line " + line + ", column " + column() + " (offset " + position() + ")";
	}

	/// Closes the stream, if there is one.
	@Override
	public void close() {
		if (in != null) {
			try {
				in.close();
			}
			catch (IOException e) {
				throw new JsonException("Can't close the JSON input: " + e.getMessage(), e);
			}
		}
	}

	// Tokens

	private int peekCode() {
		int p = peeked;
		if (p == P_NONE) {
			p = doPeek();
			peeked = p;
		}
		return p;
	}

	private void expect(int code, String what) {
		int p = peekCode();
		if (p != code) {
			throw unexpectedToken(p, what);
		}
		peeked = P_NONE;
	}

	private int doPeek() {
		int c;
		switch (stack[depth - 1]) {
			case ARRAY_EMPTY -> {
				c = nextNonWhitespace();
				if (c == ']') {
					pos++;
					return P_END_ARRAY;
				}
				stack[depth - 1] = ARRAY;
			}
			case ARRAY -> {
				c = nextNonWhitespace();
				if (c == ']') {
					pos++;
					return P_END_ARRAY;
				}
				if (c != ',') {
					throw unexpected(c, "',' or ']'");
				}
				pos++;
				c = nextNonWhitespace();
			}
			case OBJECT_EMPTY, OBJECT -> {
				c = nextNonWhitespace();
				if (c == '}') {
					pos++;
					return P_END_OBJECT;
				}
				if (stack[depth - 1] == OBJECT) {
					if (c != ',') {
						throw unexpected(c, "',' or '}'");
					}
					pos++;
					c = nextNonWhitespace();
				}
				if (c != '"') {
					throw unexpected(c, "a name");
				}
				pos++;
				return P_NAME;
			}
			case OBJECT_VALUE -> {
				stack[depth - 1] = OBJECT;
				c = nextNonWhitespace();
			}
			default -> {
				c = nextNonWhitespace();
				if (c < 0) {
					return P_END;
				}
				if (c == 0xEF && position() == 0) {
					skipByteOrderMark();
					c = nextNonWhitespace();
					if (c < 0) {
						return P_END;
					}
				}
			}
		}
		return valueCode(c);
	}

	private int valueCode(int c) {
		return switch (c) {
			case '{' -> {
				pos++;
				yield P_BEGIN_OBJECT;
			}
			case '[' -> {
				pos++;
				yield P_BEGIN_ARRAY;
			}
			case '"' -> {
				pos++;
				yield P_STRING;
			}
			case 't' -> {
				consumeLiteral(TRUE);
				yield P_TRUE;
			}
			case 'f' -> {
				consumeLiteral(FALSE);
				yield P_FALSE;
			}
			case 'n' -> {
				consumeLiteral(NULL);
				yield P_NULL;
			}
			case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> P_NUMBER;
			default -> throw unexpected(c, "a value");
		};
	}

	private void push(int scope) {
		if (depth > MAX_DEPTH) {
			throw error("JSON nests deeper than " + MAX_DEPTH);
		}
		storeScope(depth, scope);
		depth++;
	}

	private void storeScope(int level, int scope) {
		if (level == stack.length) {
			stack = Arrays.copyOf(stack, level * 2);
		}
		stack[level] = scope;
	}

	private void colon() {
		int c = nextNonWhitespace();
		if (c != ':') {
			throw unexpected(c, "':'");
		}
		pos++;
		stack[depth - 1] = OBJECT_VALUE;
	}

	private void skipByteOrderMark() {
		ensure(3);
		if (buf[pos + 1] != (byte) 0xBB || buf[pos + 2] != (byte) 0xBF) {
			throw unexpected(0xEF, "a value");
		}
		pos += 3;
		lineStart = 3;
	}

	private static final byte[] TRUE = { 't', 'r', 'u', 'e' };

	private static final byte[] FALSE = { 'f', 'a', 'l', 's', 'e' };

	private static final byte[] NULL = { 'n', 'u', 'l', 'l' };

	private void consumeLiteral(byte[] literal) {
		int n = literal.length;
		if ((limit - pos < n && !ensureSoft(n)) || !Arrays.equals(buf, pos, pos + n, literal, 0, n)) {
			throw error("Expected " + new String(literal, StandardCharsets.US_ASCII));
		}
		pos += n;
	}

	private static String literal(int code) {
		return switch (code) {
			case P_TRUE -> "true";
			case P_FALSE -> "false";
			default -> "null";
		};
	}

	/// The next byte that isn't whitespace, unconsumed, or -1 at the end of the input.
	private int nextNonWhitespace() {
		while (true) {
			byte[] b = buf;
			int lim = limit;
			for (int p = pos; p < lim; p++) {
				int c = b[p] & 0xFF;
				if (c > ' ') {
					pos = p;
					return c;
				}
				if (c == '\n') {
					line++;
					lineStart = discarded + p + 1;
				}
				else if (c != ' ' && c != '\t' && c != '\r') {
					pos = p;
					return c;
				}
			}
			pos = lim;
			if (!fill()) {
				return -1;
			}
		}
	}

	// Input

	/// Reads more input, keeping the bytes from [#mark], or else from [#pos], on, which
	/// may move. Returns false at the end of the input.
	private boolean fill() {
		InputStream in = this.in;
		if (eof || in == null) {
			return false;
		}
		int keep = mark >= 0 ? mark : pos;
		int kept = limit - keep;
		byte[] b = buf;
		if (kept > b.length / 2) {
			if (b.length >= MAX_BUFFER_SIZE) {
				throw error("A value is too large to buffer");
			}
			byte[] grown = new byte[(int) Math.min(MAX_BUFFER_SIZE, b.length * 2L)];
			System.arraycopy(b, keep, grown, 0, kept);
			buf = b = grown;
		}
		else if (keep > 0) {
			System.arraycopy(b, keep, b, 0, kept);
		}
		discarded += keep;
		pos -= keep;
		if (mark >= 0) {
			mark -= keep;
		}
		limit = kept;
		try {
			int n;
			do {
				n = in.read(b, kept, b.length - kept);
			}
			while (n == 0);
			if (n < 0) {
				eof = true;
				return false;
			}
			limit = kept + n;
			return true;
		}
		catch (IOException e) {
			throw new JsonException("Can't read JSON at " + location() + ": " + e.getMessage(), e);
		}
	}

	/// Makes `n` bytes from [#pos] available. Returns false if the input ends first.
	private boolean ensureSoft(int n) {
		while (limit - pos < n) {
			if (!fill()) {
				return false;
			}
		}
		return true;
	}

	private void ensure(int n) {
		if (!ensureSoft(n)) {
			throw error("Unexpected end of input");
		}
	}

	// Strings

	static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class,
			ByteOrder.LITTLE_ENDIAN);

	static final boolean SWAR = !Boolean.getBoolean("com.snaphop.jsonl.noswar");

	/// Bytes that end a run of plain string bytes: `"`, `\` and control characters.
	private static final boolean[] STOP = new boolean[256];

	static {
		for (int c = 0; c < 0x20; c++) {
			STOP[c] = true;
		}
		STOP['"'] = true;
		STOP['\\'] = true;
	}

	static final long ONES = 0x0101010101010101L;

	static final long HIGHS = 0x8080808080808080L;

	/// The index of the first `"`, `\` or control character in `b[p, lim)`, or `lim`.
	static int scanString(byte[] b, int p, int lim) {
		// Most strings are short, and a byte at a time is faster for them; longer
		// ones go on 8 bytes at a time, in a method of their own so that this one
		// stays small enough to inline.
		for (int head = Math.min(lim, p + 16); p < head; p++) {
			if (STOP[b[p] & 0xFF]) {
				return p;
			}
		}
		return p == lim ? p : scanLong(b, p, lim);
	}

	private static int scanLong(byte[] b, int p, int lim) {
		if (SWAR) {
			for (; p <= lim - 8; p += 8) {
				long w = (long) LONGS.get(b, p);
				long q = w ^ 0x2222222222222222L;
				long s = w ^ 0x5C5C5C5C5C5C5C5CL;
				long t = (((q - ONES) & ~q) | ((s - ONES) & ~s) | ((w - 0x2020202020202020L) & ~w)) & HIGHS;
				if (t != 0) {
					return p + (Long.numberOfTrailingZeros(t) >>> 3);
				}
			}
		}
		while (p < lim && !STOP[b[p] & 0xFF]) {
			p++;
		}
		return p;
	}

	/// Reads a string whose opening quote is consumed.
	private String readString(boolean name) {
		int start = pos;
		int i = start;
		while (true) {
			i = scanString(buf, i, limit);
			if (i - start > maxStringLength) {
				pos = start;
				throw stringTooLong(name);
			}
			if (i < limit) {
				byte c = buf[i];
				if (c == '"') {
					pos = i + 1;
					return name ? Names.name(buf, start, i - start)
							: new String(buf, start, i - start, StandardCharsets.UTF_8);
				}
				if (c == '\\') {
					pos = i;
					return readEscaped(start, name);
				}
				pos = i;
				throw error("Unescaped control character in a string");
			}
			int scanned = i - start;
			pos = start;
			if (!fill()) {
				pos = limit;
				throw error("Unterminated string");
			}
			start = pos;
			i = start + scanned;
		}
	}

	/// Finishes a string from [#pos], at a backslash, given where it started.
	private String readEscaped(int start, boolean name) {
		int n = pos - start;
		byte[] out = scratch;
		if (out.length < n + 16) {
			out = new byte[Math.max(n + 16, Math.min(out.length * 2, MAX_BUFFER_SIZE))];
		}
		System.arraycopy(buf, start, out, 0, n);
		while (true) {
			if (pos == limit && !fill()) {
				throw error("Unterminated string");
			}
			byte[] b = buf;
			int lim = limit;
			int p = pos;
			int run = scanString(b, p, lim) - p;
			if (run > 0) {
				if ((long) n + run > maxStringLength) {
					pos = p + run;
					throw stringTooLong(name);
				}
				if (out.length - n < run + 4) {
					out = Arrays.copyOf(out, (int) Math.min(MAX_BUFFER_SIZE, Math.max(out.length * 2L, (long) n + run + 16)));
				}
				System.arraycopy(b, p, out, n, run);
				n += run;
				pos = p += run;
				if (p == lim) {
					continue;
				}
			}
			if (out.length - n < 4) {
				out = Arrays.copyOf(out, (int) Math.min(MAX_BUFFER_SIZE, out.length * 2L + 16));
			}
			byte c = b[p];
			pos = p + 1;
			if (c == '"') {
				scratch = out.length <= 1 << 16 ? out : EMPTY;
				return new String(out, 0, n, StandardCharsets.UTF_8);
			}
			if (c != '\\') {
				pos = p;
				throw error("Unescaped control character in a string");
			}
			n = utf8(escape(), out, n);
			if (n > maxStringLength) {
				throw stringTooLong(name);
			}
		}
	}

	private JsonException stringTooLong(boolean name) {
		return error((name ? "A name" : "A string") + " is longer than the limit of " + maxStringLength + " bytes");
	}

	/// Reads and validates the escape after a backslash.
	private int escape() {
		ensure(1);
		byte e = buf[pos++];
		return switch (e) {
			case '"', '\\', '/' -> e;
			case 'b' -> '\b';
			case 'f' -> '\f';
			case 'n' -> '\n';
			case 'r' -> '\r';
			case 't' -> '\t';
			case 'u' -> unicodeEscape();
			default -> {
				pos--;
				throw error("Invalid escape '\\" + (char) (e & 0xFF) + "'");
			}
		};
	}

	private int unicodeEscape() {
		int cp = hex4();
		if (Character.isHighSurrogate((char) cp) && ensureSoft(6) && buf[pos] == '\\' && buf[pos + 1] == 'u') {
			int at = pos;
			pos += 2;
			int low = hex4();
			if (Character.isLowSurrogate((char) low)) {
				return Character.toCodePoint((char) cp, (char) low);
			}
			pos = at;
		}
		return Character.isSurrogate((char) cp) ? 0xFFFD : cp;
	}

	private int hex4() {
		ensure(4);
		int v = 0;
		for (int i = 0; i < 4; i++) {
			int c = buf[pos];
			int d;
			if (c >= '0' && c <= '9') {
				d = c - '0';
			}
			else if (c >= 'a' && c <= 'f') {
				d = c - 'a' + 10;
			}
			else if (c >= 'A' && c <= 'F') {
				d = c - 'A' + 10;
			}
			else {
				throw error("Invalid \\u escape");
			}
			v = v << 4 | d;
			pos++;
		}
		return v;
	}

	private static int utf8(int cp, byte[] out, int n) {
		if (cp < 0x80) {
			out[n++] = (byte) cp;
		}
		else if (cp < 0x800) {
			out[n++] = (byte) (0xC0 | cp >> 6);
			out[n++] = (byte) (0x80 | (cp & 0x3F));
		}
		else if (cp < 0x10000) {
			out[n++] = (byte) (0xE0 | cp >> 12);
			out[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
			out[n++] = (byte) (0x80 | (cp & 0x3F));
		}
		else {
			out[n++] = (byte) (0xF0 | cp >> 18);
			out[n++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
			out[n++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
			out[n++] = (byte) (0x80 | (cp & 0x3F));
		}
		return n;
	}

	/// Skips a string whose opening quote is consumed.
	private void skipString() {
		while (true) {
			int i = scanString(buf, pos, limit);
			if (i < limit) {
				byte c = buf[i];
				pos = i + 1;
				if (c == '"') {
					return;
				}
				if (c != '\\') {
					pos = i;
					throw error("Unescaped control character in a string");
				}
				escape();
				continue;
			}
			pos = i;
			if (!fill()) {
				throw error("Unterminated string");
			}
		}
	}

	/// Skips an array or object whose opening bracket is consumed.
	private void skipContainer(int openingToken) {
		int level = depth;
		if (level > MAX_DEPTH) {
			throw error("JSON nests deeper than " + MAX_DEPTH);
		}
		// Reuse the part of the scope stack above the current pull-reader scopes.
		storeScope(level, openingToken == P_BEGIN_OBJECT ? '}' : ']');
		while (true) {
			byte[] b = buf;
			int lim = limit;
			int p = pos;
			while (p < lim) {
				byte c = b[p++];
				switch (c) {
					case '"' -> {
						pos = p;
						skipString();
						b = buf;
						lim = limit;
						p = pos;
					}
					case '{', '[' -> {
						if (++level > MAX_DEPTH) {
							pos = p;
							throw error("JSON nests deeper than " + MAX_DEPTH);
						}
						storeScope(level, c == '{' ? '}' : ']');
					}
					case '}', ']' -> {
						if (stack[level] != c) {
							pos = p - 1;
							throw unexpected(c, "'" + (char) stack[level] + "'");
						}
						if (level-- == depth) {
							pos = p;
							return;
						}
					}
					case '\n' -> {
						line++;
						lineStart = discarded + p;
					}
					default -> {
					}
				}
			}
			pos = p;
			if (!fill()) {
				throw error("Unexpected end of input");
			}
		}
	}

	/// Starts a raw value: sets [#mark] at its start and skips it, or returns the
	/// negated code of a literal.
	private int rawStart() {
		int p = peekCode();
		peeked = P_NONE;
		switch (p) {
			case P_BEGIN_OBJECT, P_BEGIN_ARRAY -> {
				mark = pos - 1;
				skipContainer(p);
			}
			case P_STRING -> {
				mark = pos - 1;
				skipString();
			}
			case P_NUMBER -> {
				mark = pos;
				pos = numberEnd(Integer.MAX_VALUE);
			}
			case P_TRUE, P_FALSE, P_NULL -> {
				return -p;
			}
			default -> {
				peeked = p;
				throw unexpectedToken(p, "a value");
			}
		}
		return mark;
	}

	// Numbers

	private static boolean isNumberByte(byte c) {
		return (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
	}

	private int numberBytesEnd(int start, int end) {
		byte[] b = buf;
		int p = start;
		while (p < end && isNumberByte(b[p])) {
			p++;
		}
		return p;
	}

	private void skipNumber() {
		do {
			pos = numberBytesEnd(pos, limit);
		}
		while (pos == limit && fill());
	}

	private int numberScanLimit(int cap) {
		return limit - pos <= cap ? limit : pos + cap + 1;
	}

	/// The end of the number at [#pos], which stays put. The number is in the buffer
	/// whole, or, if it's longer than `cap`, more than `cap` of it.
	private int numberEnd(int cap) {
		int p = pos;
		while (true) {
			p = numberBytesEnd(p, numberScanLimit(cap));
			if (p < limit || p - pos > cap) {
				return p;
			}
			int scanned = p - pos;
			if (!fill()) {
				return limit;
			}
			p = pos + scanned;
		}
	}

	private static final int MAX_MANTISSA_DIGITS = 18;

	// A token fits in an int-indexed buffer. An exponent beyond this bound stays
	// outside the integer and double fast paths after any decimal-point adjustment.
	private static final long MAX_TRACKED_EXPONENT = Integer.MAX_VALUE + 32L;

	/// Reads the number at [#pos], which stays put, into the `num` fields, and returns
	/// its end. Reads more input only if the number reaches the end of the buffer. One
	/// longer than [#maxNumberLength(int)] stays next.
	private int number() {
		int scanLimit = numberScanLimit(maxNumberLength);
		int end = scanNumber(pos, scanLimit, eof && scanLimit == limit);
		if (end < 0) {
			end = numberEnd(maxNumberLength);
			if (end - pos <= maxNumberLength) {
				end = scanNumber(pos, end, true);
			}
		}
		if (end - pos > maxNumberLength) {
			throw numberTooLong();
		}
		return end;
	}

	/// An error for the number at [#pos], which has more than [#maxNumberLength] bytes
	/// in the buffer.
	private JsonException numberTooLong() {
		peeked = P_NUMBER;
		return error("Number " + new String(buf, pos, Math.min(maxNumberLength, MAX_NUMBER_IN_MESSAGE),
				StandardCharsets.ISO_8859_1) + "... is longer than the limit of " + maxNumberLength + " characters");
	}

	/// Checks the number from `start` and sets the `num` fields. Returns its end, or -1
	/// if it reaches `lim` and isn't `complete`, so may go on past it.
	private int scanNumber(int start, int lim, boolean complete) {
		byte[] b = buf;
		int i = start;
		boolean negative = b[i] == '-';
		if (negative) {
			i++;
		}
		long m = 0;
		int digits = 0;
		long exponent = 0;
		boolean truncated = false;
		int intStart = i;
		while (i < lim && b[i] >= '0' && b[i] <= '9') {
			int d = b[i++] - '0';
			if (digits < MAX_MANTISSA_DIGITS) {
				m = m * 10 + d;
				if (m != 0) {
					digits++;
				}
			}
			else {
				exponent++;
				truncated |= d != 0;
			}
		}
		if (i == lim && !complete) {
			return -1;
		}
		int intDigits = i - intStart;
		if (intDigits == 0 || (intDigits > 1 && b[intStart] == '0')) {
			throw badNumber(start);
		}
		boolean integral = true;
		if (i < lim && b[i] == '.') {
			integral = false;
			int fracStart = ++i;
			while (i < lim && b[i] >= '0' && b[i] <= '9') {
				int d = b[i++] - '0';
				if (digits < MAX_MANTISSA_DIGITS) {
					m = m * 10 + d;
					if (m != 0) {
						digits++;
					}
					exponent--;
				}
				else {
					truncated |= d != 0;
				}
			}
			if (i == lim && !complete) {
				return -1;
			}
			if (i == fracStart) {
				throw badNumber(start);
			}
		}
		if (i < lim && (b[i] == 'e' || b[i] == 'E')) {
			integral = false;
			i++;
			boolean negativeExponent = false;
			if (i < lim && (b[i] == '+' || b[i] == '-')) {
				negativeExponent = b[i++] == '-';
			}
			int expStart = i;
			long e = 0;
			while (i < lim && b[i] >= '0' && b[i] <= '9') {
				if (e < MAX_TRACKED_EXPONENT) {
					e = Math.min(MAX_TRACKED_EXPONENT, e * 10 + b[i] - '0');
				}
				i++;
			}
			if (i == lim && !complete) {
				return -1;
			}
			if (i == expStart) {
				throw badNumber(start);
			}
			exponent += negativeExponent ? -e : e;
		}
		if (i < lim && isNumberByte(b[i])) {
			throw badNumber(start);
		}
		numNegative = negative;
		numMantissa = m;
		numExponent = exponent;
		numIntegral = integral;
		numTruncated = truncated;
		return i;
	}

	/// An error naming the whole of the bad number at `start`.
	private JsonException badNumber(int start) {
		pos = start;
		int end = numberEnd(maxNumberLength);
		if (end - pos > maxNumberLength) {
			return numberTooLong();
		}
		return error("Invalid number '" + numberText(pos, end) + "'");
	}

	private static final double[] POWERS_OF_TEN = { 1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
			1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22 };

	/// The scanned number in `buf[start, end)` as a double.
	private double toDouble(int start, int end) {
		long m = numMantissa;
		if (!numTruncated && m < 1L << 53 && numExponent >= -22 && numExponent <= 22) {
			// Exact: both operands are exact doubles, so IEEE rounds once (Clinger).
			int e = (int) numExponent;
			double d = m;
			d = e < 0 ? d / POWERS_OF_TEN[-e] : d * POWERS_OF_TEN[e];
			return numNegative ? -d : d;
		}
		return Double.parseDouble(new String(buf, start, end - start, StandardCharsets.ISO_8859_1));
	}

	/// Reads a number as a long, or, if `asInt`, as an int. On failure, the number is
	/// still next.
	private long readLong(boolean asInt) {
		int end = number();
		int start = pos;
		Long v = numIntegral && numExponent == 0 ? Long.valueOf(numNegative ? -numMantissa : numMantissa)
				: wholeLong(start, end);
		if (v == null || (asInt && v.intValue() != v.longValue())) {
			peeked = P_NUMBER;
			throw error("Number " + numberText(start, end) + " isn't " + (asInt ? "an int" : "a long"));
		}
		pos = end;
		return v;
	}

	/// The scanned number in `buf[start, end)` as a long, or null if it isn't a whole
	/// number that fits. Linear in its length, where `BigDecimal` is quadratic.
	private @Nullable Long wholeLong(int start, int end) {
		long m = numMantissa;
		if (m == 0) {
			return 0L; // every digit is 0, whatever the exponent
		}
		// m holds the first 18 significant digits, and the number is about m * 10^numExponent,
		// so it has this many digits before the point.
		long whole = Long.toString(m).length() + numExponent;
		if (whole < 1 || whole > 19) {
			return null;
		}
		long v = 0; // negative as it goes, so Long.MIN_VALUE fits
		int taken = 0;
		boolean significant = false;
		for (int i = numNegative ? start + 1 : start; i < end; i++) {
			int c = buf[i];
			if (c == 'e' || c == 'E') {
				break;
			}
			if (c == '.' || (c == '0' && !significant)) {
				continue;
			}
			significant = true;
			int d = c - '0';
			if (taken < whole) {
				if (v < Long.MIN_VALUE / 10 || v * 10 < Long.MIN_VALUE + d) {
					return null;
				}
				v = v * 10 - d;
				taken++;
			}
			else if (d != 0) {
				return null; // a fraction
			}
		}
		for (; taken < whole; taken++) {
			if (v < Long.MIN_VALUE / 10) {
				return null;
			}
			v *= 10;
		}
		if (numNegative) {
			return v;
		}
		return v == Long.MIN_VALUE ? null : -v;
	}

	/// The number in `buf[start, end)` for a message, cut short if it's long.
	private String numberText(int start, int end) {
		int length = end - start;
		if (length <= MAX_NUMBER_IN_MESSAGE) {
			return new String(buf, start, length, StandardCharsets.ISO_8859_1);
		}
		return new String(buf, start, MAX_NUMBER_IN_MESSAGE, StandardCharsets.ISO_8859_1) + "... (" + length
				+ " characters)";
	}

	/// How much of a number a message shows.
	private static final int MAX_NUMBER_IN_MESSAGE = 40;

	private Object readNumber() {
		int end = number();
		int start = pos;
		pos = end;
		if (numIntegral) {
			if (numExponent == 0) {
				return numNegative ? -numMantissa : numMantissa;
			}
			var big = new BigInteger(new String(buf, start, end - start, StandardCharsets.ISO_8859_1));
			return big.bitLength() < 64 ? (Object) big.longValue() : big;
		}
		return toDouble(start, end);
	}

	// Trees

	/// Reads an object whose `{` is consumed, and whose nesting is `level`, from 1.
	private Map<String, @Nullable Object> readObject(int level) {
		if (level > MAX_DEPTH) {
			throw error("JSON nests deeper than " + MAX_DEPTH);
		}
		var map = new LinkedHashMap<String, @Nullable Object>();
		int c = nextNonWhitespace();
		if (c == '}') {
			pos++;
			return map;
		}
		while (true) {
			if (c != '"') {
				throw unexpected(c, "a name");
			}
			pos++;
			String name = readString(true);
			c = nextNonWhitespace();
			if (c != ':') {
				throw unexpected(c, "':'");
			}
			pos++;
			map.put(name, readTree(nextNonWhitespace(), level));
			c = nextNonWhitespace();
			if (c == ',') {
				pos++;
				c = nextNonWhitespace();
			}
			else if (c == '}') {
				pos++;
				return map;
			}
			else {
				throw unexpected(c, "',' or '}'");
			}
		}
	}

	private List<@Nullable Object> readArray(int level) {
		if (level > MAX_DEPTH) {
			throw error("JSON nests deeper than " + MAX_DEPTH);
		}
		var list = new ArrayList<@Nullable Object>();
		int c = nextNonWhitespace();
		if (c == ']') {
			pos++;
			return list;
		}
		while (true) {
			list.add(readTree(c, level));
			c = nextNonWhitespace();
			if (c == ',') {
				pos++;
				c = nextNonWhitespace();
			}
			else if (c == ']') {
				pos++;
				return list;
			}
			else {
				throw unexpected(c, "',' or ']'");
			}
		}
	}

	private @Nullable Object readTree(int c, int level) {
		return switch (c) {
			case '{' -> {
				pos++;
				yield readObject(level + 1);
			}
			case '[' -> {
				pos++;
				yield readArray(level + 1);
			}
			case '"' -> {
				pos++;
				yield readString(false);
			}
			case 't' -> {
				consumeLiteral(TRUE);
				yield true;
			}
			case 'f' -> {
				consumeLiteral(FALSE);
				yield false;
			}
			case 'n' -> {
				consumeLiteral(NULL);
				yield null;
			}
			case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readNumber();
			default -> throw unexpected(c, "a value");
		};
	}

	// Errors

	private JsonException error(String message) {
		return new JsonException(message + " at " + location());
	}

	private JsonException unexpected(int c, String expected) {
		if (c < 0) {
			return error("Expected " + expected + " but the input ended");
		}
		String found = c >= 0x20 && c < 0x7F ? "'" + (char) c + "'" : "byte 0x%02X".formatted(c);
		return error("Expected " + expected + " but found " + found);
	}

	private JsonException unexpectedToken(int code, String expected) {
		String found = switch (code) {
			case P_BEGIN_OBJECT -> "'{'";
			case P_END_OBJECT -> "'}'";
			case P_BEGIN_ARRAY -> "'['";
			case P_END_ARRAY -> "']'";
			case P_NAME -> "a name";
			case P_STRING -> "a string";
			case P_NUMBER -> "a number";
			case P_TRUE -> "true";
			case P_FALSE -> "false";
			case P_NULL -> "null";
			default -> "the end of the input";
		};
		return error("Expected " + expected + " but found " + found);
	}

}
