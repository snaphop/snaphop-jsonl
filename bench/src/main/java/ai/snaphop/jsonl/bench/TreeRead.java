package ai.snaphop.jsonl.bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.alibaba.fastjson2.JSON;

import ai.snaphop.jsonl.Json;

/// UTF-8 bytes to maps and lists.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "-Xmx4g", "-Djsonb.parserMaxStringBuffer=100000000" })
public class TreeRead {

	@Param({ "users-1k", "users-100k", "users-1m", "i18n-100k", "export-1m", "geo-1m" })
	public String payload;

	byte[] bytes;

	@Setup
	public void setup() {
		bytes = Payloads.bytes(payload);
	}

	@Benchmark
	public Object jsonl() {
		return Json.read(bytes);
	}

	@Benchmark
	public Object avaje() {
		return Libraries.AVAJE.fromJson(bytes);
	}

	@Benchmark
	public Object jackson() {
		return Libraries.JACKSON.readValue(bytes, Object.class);
	}

	@Benchmark
	public Object dslJson() throws Exception {
		return Libraries.DSL.deserialize(Object.class, bytes, bytes.length);
	}

	@Benchmark
	public Object fastjson2Unsafe() {
		return JSON.parse(bytes, Libraries.FASTJSON_FEATURES);
	}

	@Benchmark
	public Object foryUnsafe() {
		return Libraries.FORY.fromJson(bytes, Object.class);
	}

}
