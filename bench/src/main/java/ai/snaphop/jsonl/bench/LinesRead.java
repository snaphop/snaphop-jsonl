package ai.snaphop.jsonl.bench;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

import com.alibaba.fastjson2.JSON;

import ai.snaphop.jsonl.JsonReader;
import tools.jackson.databind.MappingIterator;

/// A JSON lines backup read from a stream, line by line into maps and lists: 20,000
/// task events, then 4 files of 500 KB (1 MB of hex each).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "-Xmx4g", "-Djsonb.parserMaxStringBuffer=100000000" })
public class LinesRead {

	byte[] bytes;

	@Setup
	public void setup() {
		bytes = Payloads.exportLines(20_000, 4, 500_000);
	}

	@Benchmark
	public long jsonl() {
		long n = 0;
		try (var r = JsonReader.of(new ByteArrayInputStream(bytes))) {
			while (r.hasNext()) {
				n += r.readValue().hashCode();
			}
		}
		return n;
	}

	/// A line at a time, as a restore with avaje must.
	@Benchmark
	public long avaje() throws IOException {
		long n = 0;
		try (var in = lines()) {
			String line;
			while ((line = in.readLine()) != null) {
				n += Libraries.AVAJE.fromJson(line).hashCode();
			}
		}
		return n;
	}

	@Benchmark
	public long jackson() {
		long n = 0;
		try (MappingIterator<Object> it = Libraries.JACKSON.readerFor(Object.class).readValues(new ByteArrayInputStream(bytes))) {
			while (it.hasNext()) {
				n += it.next().hashCode();
			}
		}
		return n;
	}

	@Benchmark
	public long fastjson2Unsafe() throws IOException {
		long n = 0;
		try (var in = lines()) {
			String line;
			while ((line = in.readLine()) != null) {
				n += JSON.parse(line, Libraries.FASTJSON_FEATURES).hashCode();
			}
		}
		return n;
	}

	private BufferedReader lines() {
		return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
	}


}
