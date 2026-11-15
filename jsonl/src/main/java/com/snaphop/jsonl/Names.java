package com.snaphop.jsonl;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Member names seen recently, so a name repeated across objects (as in every line of
/// a dump) is one `String`, not one per object.
///
/// Shared by all readers. Each slot holds an immutable entry, so racing threads at
/// worst replace each other's entries.
final class Names {

	private Names() {
	}

	private static final int MAX_LENGTH = 32;

	private static final Entry[] ENTRIES = new Entry[4096];

	/// Final fields, so an entry read by another thread is whole.
	private static final class Entry {

		final int hash;

		final byte[] bytes;

		final String name;

		Entry(int hash, byte[] bytes, String name) {
			this.hash = hash;
			this.bytes = bytes;
			this.name = name;
		}

	}

	static String name(byte[] b, int offset, int length) {
		if (length > MAX_LENGTH) {
			return new String(b, offset, length, StandardCharsets.UTF_8);
		}
		int h = 0;
		for (int i = offset, end = offset + length; i < end; i++) {
			h = 31 * h + b[i];
		}
		int slot = (h ^ h >>> 12) & ENTRIES.length - 1;
		Entry e = ENTRIES[slot];
		if (e != null && e.hash == h && Arrays.equals(e.bytes, 0, e.bytes.length, b, offset, offset + length)) {
			return e.name;
		}
		String name = new String(b, offset, length, StandardCharsets.UTF_8);
		ENTRIES[slot] = new Entry(h, Arrays.copyOfRange(b, offset, offset + length), name);
		return name;
	}

}
