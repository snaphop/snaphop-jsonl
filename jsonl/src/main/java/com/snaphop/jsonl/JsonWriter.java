package com.snaphop.jsonl;

import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

import org.jspecify.annotations.Nullable;

/// Writes UTF-8 JSON to a stream or to bytes. Not thread safe.
///
/// Values written outside any array or object go on lines of their own, as JSON lines
/// does. Nulls are always written. A lone surrogate in a string is written as U+FFFD,
/// as [JsonReader] reads an escaped one. The writer checks that names go only in
/// objects, but otherwise trusts its caller to pair `begin` and `end`.
///
/// ```
/// var w = JsonWriter.ofBytes();
/// w.beginObject().name("name").value("Zürich").name("population").value(447_082).endObject();
/// byte[] json = w.toBytes();
/// ```
public final class JsonWriter implements AutoCloseable, Flushable {

	/// The default size of the buffer.
	public static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

	/// Strings at least this long are encoded by [String#getBytes] and copied, rather
	/// than one character at a time.
	private static final int LONG_STRING = 64;

	private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 16;

	// Containers: each level of the stack is one of these.
	private static final byte ARRAY_EMPTY = 0;

	private static final byte ARRAY = 1;

	private static final byte OBJECT_EMPTY = 2;

	private static final byte OBJECT = 3;

	private final @Nullable OutputStream out;

	private byte[] buf;

	private int pos;

	private byte[] stack = new byte[32];

	private int depth;

	private boolean afterName;

	private boolean anyTopLevel;

	private byte @Nullable [] indent;

	private JsonWriter(@Nullable OutputStream out, int bufferSize) {
		this.out = out;
		this.buf = new byte[bufferSize];
	}

	/// Writes to a stream, through a buffer; [#flush()] or [#close()] when done.
	/// @param out where the UTF-8 JSON goes.
	/// @return a writer.
	public static JsonWriter of(OutputStream out) {
		return of(out, DEFAULT_BUFFER_SIZE);
	}

	/// Writes to a stream, through a buffer; [#flush()] or [#close()] when done.
	/// @param out where the UTF-8 JSON goes.
	/// @param bufferSize the size of the buffer, at least 16.
	/// @return a writer.
	public static JsonWriter of(OutputStream out, int bufferSize) {
		if (bufferSize < 16) {
			throw new IllegalArgumentException("bufferSize " + bufferSize + " is under 16");
		}
		return new JsonWriter(out, bufferSize);
	}

	/// Writes to memory; [#toBytes()] or [#toString()] when done.
	/// @return a writer.
	public static JsonWriter ofBytes() {
		return new JsonWriter(null, 256);
	}

	/// Indents nested values on lines of their own, by `indent` per level.
	/// @param indent spaces or tabs, or empty for compact JSON.
	/// @return this.
	public JsonWriter indent(String indent) {
		this.indent = indent.isEmpty() ? null : indent.getBytes(StandardCharsets.UTF_8);
		return this;
	}

	// Structure

	/// Writes `{`.
	/// @return this.
	public JsonWriter beginObject() {
		return open(OBJECT_EMPTY, '{');
	}

	/// Writes `}`.
	/// @return this.
	public JsonWriter endObject() {
		return close(OBJECT_EMPTY, '}');
	}

	/// Writes `[`.
	/// @return this.
	public JsonWriter beginArray() {
		return open(ARRAY_EMPTY, '[');
	}

	/// Writes `]`.
	/// @return this.
	public JsonWriter endArray() {
		return close(ARRAY_EMPTY, ']');
	}

	/// Writes a member's name and its colon.
	/// @param name the name.
	/// @return this.
	public JsonWriter name(String name) {
		int d = depth;
		if (d == 0 || afterName || stack[d - 1] < OBJECT_EMPTY) {
			throw new JsonException("A name goes only in an object, before its value");
		}
		if (stack[d - 1] == OBJECT) {
			ensure(1);
			buf[pos++] = ',';
		}
		else {
			stack[d - 1] = OBJECT;
		}
		if (indent != null) {
			newLine(d);
		}
		string(name);
		ensure(2);
		buf[pos++] = ':';
		if (indent != null) {
			buf[pos++] = ' ';
		}
		afterName = true;
		return this;
	}

	// Values

	/// Writes a string, or `null`.
	/// @param value the string.
	/// @return this.
	public JsonWriter value(@Nullable String value) {
		if (value == null) {
			return nullValue();
		}
		separate();
		string(value);
		return this;
	}

	/// Writes a number.
	/// @param value the number.
	/// @return this.
	public JsonWriter value(long value) {
		separate();
		writeLong(value);
		return this;
	}

	/// Writes a number.
	/// @param value the number, which must be finite: JSON has no NaN or infinity.
	/// @return this.
	public JsonWriter value(double value) {
		if (!Double.isFinite(value)) {
			throw new JsonException("JSON has no " + value);
		}
		separate();
		ascii(Double.toString(value));
		return this;
	}

	/// Writes `true` or `false`.
	/// @param value the boolean.
	/// @return this.
	public JsonWriter value(boolean value) {
		separate();
		ascii(value ? "true" : "false");
		return this;
	}

	/// Writes `null`.
	/// @return this.
	public JsonWriter nullValue() {
		separate();
		ascii("null");
		return this;
	}

	/// Writes a value of a tree: a map (keys as strings), list, other iterable, array of
	/// objects, string, number, boolean, [JsonWritable] or `null`.
	///
	/// Anything else, such as a `UUID`, an `Instant` or an `Optional`, throws rather
	/// than being given a form here: convert it first, to a string say, or wrap it in a
	/// [JsonWritable], so that its JSON is the caller's choice.
	/// @param value the value.
	/// @return this.
	/// @throws JsonException for a value of another type, or a double that isn't
	///   finite.
	public JsonWriter value(@Nullable Object value) {
		switch (value) {
			case null -> nullValue();
			case String s -> value(s);
			case Map<?, ?> map -> {
				beginObject();
				for (var e : map.entrySet()) {
					name(String.valueOf(e.getKey()));
					value(e.getValue());
				}
				endObject();
			}
			case List<?> list when list instanceof RandomAccess -> {
				beginArray();
				for (int i = 0, n = list.size(); i < n; i++) {
					value(list.get(i));
				}
				endArray();
			}
			case Iterable<?> items -> {
				beginArray();
				for (Object item : items) {
					value(item);
				}
				endArray();
			}
			case Long n -> value(n.longValue());
			case Integer n -> value(n.longValue());
			case Double n -> value(n.doubleValue());
			case Boolean b -> value(b.booleanValue());
			case JsonWritable w -> w.writeJson(this);
			case Float n -> value(n.doubleValue());
			case Short n -> value(n.longValue());
			case Byte n -> value(n.longValue());
			case BigInteger n -> rawValue(n.toString());
			case BigDecimal n -> rawValue(n.toString());
			case Object[] items -> value(Arrays.asList(items));
			case CharSequence s -> value(s.toString());
			default -> throw new JsonException("Can't write a " + value.getClass().getName() + " as JSON");
		}
		return this;
	}

	/// Writes JSON as it is, unchecked.
	/// @param json one JSON value.
	/// @return this.
	public JsonWriter rawValue(String json) {
		separate();
		if (json.length() >= LONG_STRING) {
			byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
			if (utf8.length == json.length()) {
				ascii(json, utf8, false);
				return this;
			}
		}
		chars(json, false);
		return this;
	}

	/// Writes UTF-8 JSON as it is, unchecked.
	/// @param json one JSON value.
	/// @return this.
	public JsonWriter rawValue(byte[] json) {
		return rawValue(json, 0, json.length);
	}

	/// Writes UTF-8 JSON as it is, unchecked.
	/// @param json holds one JSON value.
	/// @param offset where it starts.
	/// @param length how many bytes it has.
	/// @return this.
	public JsonWriter rawValue(byte[] json, int offset, int length) {
		java.util.Objects.checkFromIndexSize(offset, length, json.length);
		separate();
		bytes(json, offset, length);
		return this;
	}

	// Output

	/// The JSON written so far, by a writer from [#ofBytes()].
	/// @return a copy of the bytes.
	public byte[] toBytes() {
		requireMemory();
		return Arrays.copyOf(buf, pos);
	}

	/// The JSON written so far, by a writer from [#ofBytes()].
	/// @return the JSON, or for a writer to a stream, a description.
	@Override
	public String toString() {
		if (out != null) {
			return "JsonWriter[" + out + "]";
		}
		return new String(buf, 0, pos, StandardCharsets.UTF_8);
	}

	/// How many bytes are written so far, by a writer from [#ofBytes()].
	/// @return the size.
	public int size() {
		requireMemory();
		return pos;
	}

	/// Forgets what's written, by a writer from [#ofBytes()], to write again with the
	/// same buffer.
	public void reset() {
		requireMemory();
		pos = 0;
		depth = 0;
		afterName = false;
		anyTopLevel = false;
	}

	/// Writes the buffer to the stream and flushes it.
	@Override
	public void flush() {
		if (out != null) {
			flushBuffer();
			try {
				out.flush();
			}
			catch (IOException e) {
				throw new JsonException("Can't write JSON: " + e.getMessage(), e);
			}
		}
	}

	/// Flushes and closes the stream.
	@Override
	public void close() {
		if (out != null) {
			flush();
			try {
				out.close();
			}
			catch (IOException e) {
				throw new JsonException("Can't write JSON: " + e.getMessage(), e);
			}
		}
	}

	// Internals

	private void requireMemory() {
		if (out != null) {
			throw new IllegalStateException("This writer writes to a stream");
		}
	}

	private JsonWriter open(byte container, char bracket) {
		separate();
		if (depth >= JsonReader.MAX_DEPTH) {
			throw new JsonException("JSON nests deeper than " + JsonReader.MAX_DEPTH + " (is a tree cyclic?)");
		}
		if (depth == stack.length) {
			stack = Arrays.copyOf(stack, depth * 2);
		}
		stack[depth++] = container;
		ensure(1);
		buf[pos++] = (byte) bracket;
		return this;
	}

	private JsonWriter close(byte empty, char bracket) {
		int d = depth;
		if (d == 0 || (stack[d - 1] & ~1) != empty || afterName) {
			throw new JsonException("Unexpected '" + bracket + "'");
		}
		depth = d - 1;
		if (indent != null && stack[d - 1] != empty) {
			newLine(d - 1);
		}
		ensure(1);
		buf[pos++] = (byte) bracket;
		return this;
	}

	/// What goes before a value: a comma, a new line or nothing.
	private void separate() {
		if (afterName) {
			afterName = false;
			return;
		}
		int d = depth;
		if (d == 0) {
			if (anyTopLevel) {
				ensure(1);
				buf[pos++] = '\n';
			}
			anyTopLevel = true;
			return;
		}
		byte container = stack[d - 1];
		if (container == ARRAY) {
			ensure(1);
			buf[pos++] = ',';
		}
		else if (container == ARRAY_EMPTY) {
			stack[d - 1] = ARRAY;
		}
		else {
			throw new JsonException("A value in an object needs a name");
		}
		if (indent != null) {
			newLine(d);
		}
	}

	private void newLine(int level) {
		byte[] in = indent;
		if (in == null) {
			return;
		}
		ensure(1 + in.length * level);
		buf[pos++] = '\n';
		for (int i = 0; i < level; i++) {
			System.arraycopy(in, 0, buf, pos, in.length);
			pos += in.length;
		}
	}

	private void ensure(int n) {
		if (buf.length - pos < n) {
			if (out != null) {
				flushBuffer();
				if (buf.length >= n) {
					return;
				}
			}
			long size = Math.max(buf.length * 2L, (long) pos + n);
			if (size > MAX_BUFFER_SIZE) {
				if ((long) pos + n > MAX_BUFFER_SIZE) {
					throw new JsonException("JSON is too large for memory");
				}
				size = MAX_BUFFER_SIZE;
			}
			buf = Arrays.copyOf(buf, (int) size);
		}
	}

	private void flushBuffer() {
		OutputStream o = out;
		if (o != null && pos > 0) {
			try {
				o.write(buf, 0, pos);
			}
			catch (IOException e) {
				throw new JsonException("Can't write JSON: " + e.getMessage(), e);
			}
			pos = 0;
		}
	}

	private void bytes(byte[] b, int offset, int length) {
		if (out != null && length > buf.length) {
			flushBuffer();
			try {
				out.write(b, offset, length);
			}
			catch (IOException e) {
				throw new JsonException("Can't write JSON: " + e.getMessage(), e);
			}
			return;
		}
		ensure(length);
		System.arraycopy(b, offset, buf, pos, length);
		pos += length;
	}

	private void ascii(String s) {
		int n = s.length();
		ensure(n);
		byte[] b = buf;
		int p = pos;
		for (int i = 0; i < n; i++) {
			b[p++] = (byte) s.charAt(i);
		}
		pos = p;
	}

	private static final byte[] DIGIT_TENS = new byte[100];

	private static final byte[] DIGIT_ONES = new byte[100];

	static {
		for (int i = 0; i < 100; i++) {
			DIGIT_TENS[i] = (byte) ('0' + i / 10);
			DIGIT_ONES[i] = (byte) ('0' + i % 10);
		}
	}

	private void writeLong(long v) {
		ensure(20);
		if (v < 0) {
			if (v == Long.MIN_VALUE) {
				ascii("-9223372036854775808");
				return;
			}
			buf[pos++] = '-';
			v = -v;
		}
		int n = 1;
		for (long t = v; t >= 10; t /= 10) {
			n++;
		}
		byte[] b = buf;
		int p = pos + n;
		pos = p;
		while (v >= 100) {
			int r = (int) (v % 100);
			v /= 100;
			b[--p] = DIGIT_ONES[r];
			b[--p] = DIGIT_TENS[r];
		}
		int r = (int) v;
		b[--p] = DIGIT_ONES[r];
		if (r >= 10) {
			b[--p] = DIGIT_TENS[r];
		}
	}

	/// Characters escaped other than as a six character unicode escape, by their escape
	/// letter; 0 for none, 1 for a unicode escape.
	private static final byte[] ESCAPES = new byte[128];

	static {
		for (int c = 0; c < 0x20; c++) {
			ESCAPES[c] = 1;
		}
		ESCAPES['"'] = '"';
		ESCAPES['\\'] = '\\';
		ESCAPES['\b'] = 'b';
		ESCAPES['\f'] = 'f';
		ESCAPES['\n'] = 'n';
		ESCAPES['\r'] = 'r';
		ESCAPES['\t'] = 't';
	}

	private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

	private static int escape(byte[] b, int p, int c) {
		byte e = ESCAPES[c];
		b[p++] = '\\';
		if (e != 1) {
			b[p++] = e;
		}
		else {
			b[p++] = 'u';
			b[p++] = '0';
			b[p++] = '0';
			b[p++] = HEX[c >> 4];
			b[p++] = HEX[c & 0xF];
		}
		return p;
	}

	/// Writes a quoted, escaped string.
	private void string(String s) {
		if (s.length() >= LONG_STRING) {
			byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
			if (utf8.length == s.length()) {
				ensure(1);
				buf[pos++] = '"';
				ascii(s, utf8, true);
				ensure(1);
				buf[pos++] = '"';
				return;
			}
		}
		ensure(1);
		buf[pos++] = '"';
		chars(s, true);
		ensure(1);
		buf[pos++] = '"';
	}

	/// Encodes `s` as UTF-8, a chunk that fits the buffer at a time, escaping it for a
	/// string if `escape`. A lone surrogate becomes U+FFFD, as reading one does.
	private void chars(String s, boolean escape) {
		int n = s.length();
		int i = 0;
		while (i < n) {
			// Each char takes at most 6 bytes; a pair takes 4 for 2, so one may run
			// past the chunk's end.
			int room = (buf.length - pos) / 6;
			if (room < 2) {
				ensure(Math.min(n - i, 4096) * 6 + 12);
				room = (buf.length - pos) / 6;
			}
			int end = Math.min(n, i + room - 1);
			byte[] b = buf;
			int p = pos;
			for (; i < end; i++) {
				char c = s.charAt(i);
				if (c < 0x80) {
					if (!escape || ESCAPES[c] == 0) {
						b[p++] = (byte) c;
					}
					else {
						p = escape(b, p, c);
					}
				}
				else if (c < 0x800) {
					b[p++] = (byte) (0xC0 | (c >> 6));
					b[p++] = (byte) (0x80 | (c & 0x3F));
				}
				else if (!Character.isSurrogate(c)) {
					b[p++] = (byte) (0xE0 | (c >> 12));
					b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
					b[p++] = (byte) (0x80 | (c & 0x3F));
				}
				else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
					int cp = Character.toCodePoint(c, s.charAt(++i));
					b[p++] = (byte) (0xF0 | (cp >> 18));
					b[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
					b[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
					b[p++] = (byte) (0x80 | (cp & 0x3F));
				}
				else {
					b[p++] = (byte) 0xEF;
					b[p++] = (byte) 0xBF;
					b[p++] = (byte) 0xBD;
				}
			}
			pos = p;
		}
	}

	/// Writes `s` from `u`, its UTF-8 bytes, one for each char: so ASCII, except that a
	/// lone surrogate became `?`, which this checks. Copies the runs between the bytes
	/// that need a look, escaping them for a string if `escape`.
	private void ascii(String s, byte[] u, boolean escape) {
		int i = 0;
		int n = u.length;
		while (i < n) {
			int stop = scanAscii(u, i, n);
			if (stop > i) {
				bytes(u, i, stop - i);
			}
			if (stop == n) {
				break;
			}
			ensure(6);
			byte c = u[stop];
			if (c == '?' && s.charAt(stop) != '?') {
				buf[pos++] = (byte) 0xEF;
				buf[pos++] = (byte) 0xBF;
				buf[pos++] = (byte) 0xBD;
			}
			else if (escape && ESCAPES[c] != 0) {
				pos = escape(buf, pos, c);
			}
			else {
				buf[pos++] = c;
			}
			i = stop + 1;
		}
	}

	/// The index of the first `"`, `\`, `?` or control character in `b[p, lim)`, or
	/// `lim`.
	private static int scanAscii(byte[] b, int p, int lim) {
		if (JsonReader.SWAR) {
			for (; p <= lim - 8; p += 8) {
				long w = (long) JsonReader.LONGS.get(b, p);
				long q = w ^ 0x2222222222222222L;
				long s = w ^ 0x5C5C5C5C5C5C5C5CL;
				long m = w ^ 0x3F3F3F3F3F3F3F3FL;
				long t = (((q - JsonReader.ONES) & ~q) | ((s - JsonReader.ONES) & ~s) | ((m - JsonReader.ONES) & ~m)
						| ((w - 0x2020202020202020L) & ~w)) & JsonReader.HIGHS;
				if (t != 0) {
					return p + (Long.numberOfTrailingZeros(t) >>> 3);
				}
			}
		}
		for (; p < lim; p++) {
			byte c = b[p];
			if (c < 0x20 || c == '"' || c == '\\' || c == '?') {
				return p;
			}
		}
		return p;
	}

}
