package com.snaphop.jsonl.bench;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.snaphop.jsonl.JsonReader;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/// A geon dump read from a stream token by token, without building trees: every
/// string's length and every number, summed. 5,000 places, a tenth of them with
/// polygons of thousands of positions.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "-Xmx4g", "-Djsonb.parserMaxStringBuffer=100000000" })
public class PullRead {

	byte[] bytes;

	@Setup
	public void setup() {
		bytes = Payloads.geoLines(5_000);
	}

	@Benchmark
	public double jsonl() {
		double sum = 0;
		try (var r = JsonReader.of(new ByteArrayInputStream(bytes))) {
			while (r.hasNext()) {
				sum += walk(r);
			}
		}
		return sum;
	}

	static double walk(JsonReader r) {
		switch (r.peek()) {
			case BEGIN_OBJECT -> {
				double sum = 0;
				r.beginObject();
				while (r.hasNext()) {
					sum += r.nextName().length();
					sum += walk(r);
				}
				r.endObject();
				return sum;
			}
			case BEGIN_ARRAY -> {
				double sum = 0;
				r.beginArray();
				while (r.hasNext()) {
					sum += walk(r);
				}
				r.endArray();
				return sum;
			}
			case STRING -> {
				return r.nextString().length();
			}
			case NUMBER -> {
				return r.nextDouble();
			}
			case BOOLEAN -> {
				return r.nextBoolean() ? 1 : 0;
			}
			default -> {
				r.nextNull();
				return 0;
			}
		}
	}

	/// As geon does: lines split from the stream, each read from a `byte[]`, since
	/// avaje 3.16's stream reader can end an array early at a buffer edge.
	@Benchmark
	public double avaje() throws IOException {
		double sum = 0;
		var lines = new Lines(new ByteArrayInputStream(bytes));
		byte[] line;
		while ((line = lines.next()) != null) {
			try (var r = Libraries.AVAJE_STREAM.reader(line)) {
				r.beginStream();
				while (r.hasNextStreamElement()) {
					sum += walk(r);
				}
			}
		}
		return sum;
	}

	static double walk(io.avaje.json.JsonReader r) {
		switch (r.currentToken()) {
			case BEGIN_OBJECT -> {
				double sum = 0;
				r.beginObject();
				while (r.hasNextField()) {
					sum += r.nextField().length();
					sum += walk(r);
				}
				r.endObject();
				return sum;
			}
			case BEGIN_ARRAY -> {
				double sum = 0;
				r.beginArray();
				while (r.hasNextElement()) {
					sum += walk(r);
				}
				r.endArray();
				return sum;
			}
			case STRING -> {
				return r.readString().length();
			}
			case NUMBER -> {
				return r.readDouble();
			}
			case BOOLEAN -> {
				return r.readBoolean() ? 1 : 0;
			}
			default -> {
				r.isNullValue();
				return 0;
			}
		}
	}

	@Benchmark
	public double jackson() {
		double sum = 0;
		try (JsonParser p = Libraries.JACKSON.createParser(new ByteArrayInputStream(bytes))) {
			JsonToken t;
			while ((t = p.nextToken()) != null) {
				switch (t) {
					case PROPERTY_NAME -> sum += p.currentName().length();
					case VALUE_STRING -> sum += p.getString().length();
					case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> sum += p.getDoubleValue();
					case VALUE_TRUE -> sum += 1;
					default -> {
					}
				}
			}
		}
		return sum;
	}

	/// geon-dump's `Lines`, trimmed.
	static final class Lines {

		private final InputStream in;

		private final byte[] buffer = new byte[1 << 16];

		private int position;

		private int limit;

		private byte[] line = new byte[1 << 12];

		Lines(InputStream in) {
			this.in = in;
		}

		byte[] next() throws IOException {
			int length = 0;
			boolean any = false;
			while (true) {
				if (position == limit) {
					limit = in.read(buffer, 0, buffer.length);
					position = 0;
					if (limit <= 0) {
						limit = 0;
						if (!any) {
							return null;
						}
						break;
					}
				}
				any = true;
				int start = position;
				while (position < limit && buffer[position] != '\n') {
					position++;
				}
				int count = position - start;
				if (length + count > line.length) {
					line = Arrays.copyOf(line, Math.max(line.length * 2, length + count));
				}
				System.arraycopy(buffer, start, line, length, count);
				length += count;
				if (position < limit) {
					position++;
					break;
				}
			}
			return Arrays.copyOf(line, length);
		}

	}

}
