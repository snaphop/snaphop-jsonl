package com.snaphop.jsonl.bench;

import java.io.ByteArrayOutputStream;
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

import com.snaphop.jsonl.Json;

/// Maps and lists to UTF-8 bytes.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "-Xmx4g" })
public class TreeWrite {

	@Param({ "users-1k", "users-100k", "users-1m", "i18n-100k", "export-1m", "geo-1m" })
	public String payload;

	Object tree;

	@Setup
	public void setup() {
		tree = Payloads.tree(payload);
	}

	@Benchmark
	public byte[] jsonl() {
		return Json.writeBytes(tree);
	}

	@Benchmark
	public byte[] avaje() {
		return Libraries.AVAJE.toJsonBytes(tree);
	}

	@Benchmark
	public byte[] jackson() {
		return Libraries.JACKSON.writeValueAsBytes(tree);
	}

	@Benchmark
	public byte[] dslJson() throws Exception {
		var out = new ByteArrayOutputStream();
		Libraries.DSL.serialize(tree, out);
		return out.toByteArray();
	}

	@Benchmark
	public byte[] fastjson2Unsafe() {
		return JSON.toJSONBytes(tree);
	}

	@Benchmark
	public byte[] foryUnsafe() {
		return Libraries.FORY.toJsonBytes(tree);
	}

}
