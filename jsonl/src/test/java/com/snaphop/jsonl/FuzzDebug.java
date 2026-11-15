package com.snaphop.jsonl;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

class FuzzDebug {

	@Test
	void seed() {
		long seed = Long.getLong("debug.seed", 0);
		if (seed == 0) {
			return;
		}
		var r = new Random(seed);
		Object tree = FuzzTest.Gen.value(r, 0);
		String json = FuzzTest.Gen.format(r, tree);
		System.out.println("TREE " + FuzzTest.describe(tree));
		System.out.println("JSON " + FuzzTest.abbreviate(json));
		try (var reader = JsonReader.of(new FuzzTest.Chunks(json.getBytes(StandardCharsets.UTF_8), r), 16 + r.nextInt(50))) {
			System.out.println("PULL " + FuzzTest.describe(FuzzTest.pull(reader, r)));
		}
	}

}
