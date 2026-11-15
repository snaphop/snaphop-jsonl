package ai.snaphop.jsonl.bench;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.alibaba.fastjson2.JSON;

import ai.snaphop.jsonl.Json;

/// Checks that every library reads and writes every payload to the same values, so
/// the benchmarks compare like with like. Numbers compare as doubles, since libraries
/// differ on `Integer` or `Long`.
public final class Check {

	private Check() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("jsonb.parserMaxStringBuffer", "100000000");
		Map<String, Function<byte[], Object>> readers = new LinkedHashMap<>();
		readers.put("jsonl", Json::read);
		readers.put("avaje", Libraries.AVAJE::fromJson);
		readers.put("jackson", b -> Libraries.JACKSON.readValue(b, Object.class));
		readers.put("dslJson", b -> {
			try {
				return Libraries.DSL.deserialize(Object.class, b, b.length);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		readers.put("fastjson2", b -> JSON.parse(b, Libraries.FASTJSON_FEATURES));
		readers.put("fory", b -> Libraries.FORY.fromJson(b, Object.class));
		Map<String, Function<Object, byte[]>> writers = new LinkedHashMap<>();
		writers.put("jsonl", Json::writeBytes);
		writers.put("avaje", Libraries.AVAJE::toJsonBytes);
		writers.put("jackson", Libraries.JACKSON::writeValueAsBytes);
		writers.put("dslJson", t -> {
			try {
				var out = new ByteArrayOutputStream();
				Libraries.DSL.serialize(t, out);
				return out.toByteArray();
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		writers.put("fastjson2", JSON::toJSONBytes);
		writers.put("fory", Libraries.FORY::toJsonBytes);
		boolean ok = true;
		for (String payload : List.of("users-1k", "users-100k", "users-1m", "i18n-100k", "export-1m", "geo-1m")) {
			Object tree = Payloads.tree(payload);
			byte[] bytes = Payloads.write(tree);
			Object expected = normal(tree);
			System.out.printf("%-11s %,10d bytes%n", payload, bytes.length);
			for (var e : readers.entrySet()) {
				ok &= report(payload, "read  " + e.getKey(), () -> normal(e.getValue().apply(bytes)), expected);
			}
			for (var e : writers.entrySet()) {
				ok &= report(payload, "write " + e.getKey(), () -> normal(Json.read(e.getValue().apply(tree))), expected);
			}
		}
		System.out.println(ok ? "All agree." : "Some differ.");
	}

	interface Attempt {
		Object get() throws Exception;
	}

	static boolean report(String payload, String what, Attempt attempt, Object expected) {
		try {
			Object actual = attempt.get();
			if (!Objects.equals(expected, actual)) {
				System.out.printf("  %-16s DIFFERENT%n", what);
				return false;
			}
			return true;
		}
		catch (Exception | Error e) {
			System.out.printf("  %-16s FAILED %s%n", what, e.toString().lines().findFirst().orElse(""));
			return false;
		}
	}

	static Object normal(Object v) {
		return switch (v) {
			case Map<?, ?> m -> {
				var out = new LinkedHashMap<String, Object>();
				m.forEach((k, x) -> out.put(String.valueOf(k), normal(x)));
				yield out;
			}
			case List<?> l -> {
				var out = new ArrayList<Object>();
				l.forEach(x -> out.add(normal(x)));
				yield out;
			}
			case Number n -> n.doubleValue();
			case null -> "<null>";
			default -> v;
		};
	}

}
