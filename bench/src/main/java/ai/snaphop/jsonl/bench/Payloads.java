package ai.snaphop.jsonl.bench;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ai.snaphop.jsonl.JsonWriter;

/// Seeded payloads, the same on every run.
///
/// `users-*` follows the `Users` model and generator of
/// [java-json-benchmark](https://github.com/fabienrenaud/java-json-benchmark) (MIT),
/// as maps and lists. `i18n-*` is the same with non-ASCII text and escapes. `export-*`
/// is a JSON lines backup line holding a file's bytes in hex, and `geo-*` a Photon
/// dump place with a large polygon, as geon reads.
final class Payloads {

	private Payloads() {
	}

	static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	static final String[] I18N = { "Zürich", "Genève", "Bärau", "安", "東京都", "Москва", "Αθήνα", "😀", "\"quoted\"",
			"tab\there", "line\nbreak", "back\\slash", "plain" };

	/// The payload of a name, as UTF-8 JSON.
	static byte[] bytes(String name) {
		return write(tree(name));
	}

	static Object tree(String name) {
		var random = new Random(name.hashCode());
		return switch (name) {
			case "users-1k" -> users(random, 1_000, false);
			case "users-100k" -> users(random, 100_000, false);
			case "users-1m" -> users(random, 1_000_000, false);
			case "i18n-100k" -> users(random, 100_000, true);
			case "export-1m" -> exportFile(random, 500_000);
			case "geo-1m" -> place(random, 1, 25_000);
			default -> throw new IllegalArgumentException(name);
		};
	}

	static byte[] write(Object tree) {
		return JsonWriter.ofBytes().value(tree).toBytes();
	}

	static String text(Random r, String alphabet, int n, boolean i18n) {
		var sb = new StringBuilder(n);
		while (sb.length() < n) {
			if (i18n && r.nextInt(4) == 0) {
				sb.append(I18N[r.nextInt(I18N.length)]);
			}
			else {
				sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
			}
		}
		return sb.toString();
	}

	static Map<String, Object> users(Random r, int size, boolean i18n) {
		var users = new ArrayList<Object>();
		var root = new LinkedHashMap<String, Object>();
		root.put("users", users);
		int approx = 12;
		while (approx < size) {
			var u = new LinkedHashMap<String, Object>();
			u.put("_id", text(r, "0123456789", 20, false));
			u.put("index", (long) r.nextInt(Integer.MAX_VALUE));
			u.put("guid", text(r, ALNUM, 20, false));
			u.put("isActive", r.nextBoolean());
			u.put("balance", text(r, ALNUM, 20, false));
			u.put("picture", text(r, ALNUM, 100, false));
			u.put("age", (long) r.nextInt(100));
			u.put("eyeColor", text(r, ALNUM, 20, false));
			u.put("name", text(r, ALNUM, 20, i18n));
			u.put("gender", text(r, ALNUM, 20, false));
			u.put("company", text(r, ALNUM, 20, i18n));
			u.put("email", text(r, ALNUM, 20, false));
			u.put("phone", text(r, ALNUM, 20, false));
			u.put("address", text(r, ALNUM, 20, i18n));
			u.put("about", text(r, ALNUM, 20, i18n));
			u.put("registered", text(r, ALNUM, 20, false));
			u.put("latitude", r.nextDouble() * 90);
			u.put("longitude", r.nextDouble() * 180);
			var tags = new ArrayList<Object>();
			for (int i = 0, n = r.nextInt(50); i < n; i++) {
				tags.add(text(r, ALNUM, 10, false));
			}
			u.put("tags", tags);
			var friends = new ArrayList<Object>();
			for (int i = 0, n = r.nextInt(50); i < n; i++) {
				var f = new LinkedHashMap<String, Object>();
				f.put("id", Integer.toString(r.nextInt(10_000)));
				f.put("name", text(r, ALPHA, 30, i18n));
				friends.add(f);
			}
			u.put("friends", friends);
			u.put("greeting", text(r, ALNUM, 20, i18n));
			u.put("favoriteFruit", text(r, ALNUM, 20, false));
			users.add(u);
			approx += write(u).length + 1;
		}
		return root;
	}

	/// A backup line for a file: its bytes in hex.
	static Map<String, Object> exportFile(Random r, int fileBytes) {
		byte[] data = new byte[fileBytes];
		r.nextBytes(data);
		var row = new LinkedHashMap<String, Object>();
		row.put("workspace_id", 1L);
		row.put("id", text(r, "0123456789abcdef", 64, false));
		row.put("type", "application/octet-stream");
		row.put("size", (long) fileBytes);
		row.put("data", HexFormat.of().formatHex(data));
		row.put("created_at", "2026-11-15T09:16:37.123456-05:00");
		var line = new LinkedHashMap<String, Object>();
		line.put("log", "file");
		line.put("row", row);
		return line;
	}

	/// A backup line for an event: small and many.
	static Map<String, Object> exportEvent(Random r, long seq) {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("title", text(r, ALNUM + "    ", 30, true));
		payload.put("notes", r.nextInt(3) == 0 ? text(r, ALNUM + "    ", 200, true) : null);
		payload.put("due", r.nextBoolean() ? "2026-11-" + (10 + r.nextInt(20)) : null);
		payload.put("done", r.nextBoolean());
		payload.put("tags", List.of(text(r, ALPHA, 6, false), text(r, ALPHA, 8, false)));
		var row = new LinkedHashMap<String, Object>();
		row.put("seq", seq);
		row.put("workspace_id", 1L);
		row.put("item_id", (long) r.nextInt(100_000));
		row.put("kind", "item.updated");
		row.put("actor", "user:" + r.nextInt(10));
		row.put("at", "2026-11-15T09:16:37.123456-05:00");
		row.put("payload", payload);
		var line = new LinkedHashMap<String, Object>();
		line.put("log", "event");
		line.put("row", row);
		return line;
	}

	/// A whole export: many events, then a few files.
	static byte[] exportLines(int events, int files, int fileBytes) {
		var r = new Random(42);
		var w = JsonWriter.ofBytes();
		w.value(Map.of("format", "event-log", "version", 2L));
		for (int i = 0; i < events; i++) {
			w.value(exportEvent(r, i + 1));
		}
		for (int i = 0; i < files; i++) {
			w.value(exportFile(r, fileBytes));
		}
		return w.toBytes();
	}

	/// A Photon dump place with a polygon of `points` positions.
	static Map<String, Object> place(Random r, long id, int points) {
		var content = new LinkedHashMap<String, Object>();
		content.put("place_id", id);
		content.put("object_type", "R");
		content.put("object_id", id * 7);
		content.put("osm_key", "boundary");
		content.put("osm_value", "administrative");
		content.put("categories", List.of("osm.boundary.administrative"));
		content.put("rank_address", 16L);
		content.put("importance", r.nextDouble());
		var name = new LinkedHashMap<String, Object>();
		name.put("name", text(r, ALPHA, 12, true));
		name.put("name:de", text(r, ALPHA, 12, true));
		name.put("name:fr", text(r, ALPHA, 12, true));
		name.put("name:zh", "東京都" + id);
		content.put("name", name);
		var address = new LinkedHashMap<String, Object>();
		address.put("city", text(r, ALPHA, 10, true));
		address.put("state", text(r, ALPHA, 10, true));
		content.put("address", address);
		content.put("country_code", "ch");
		double lon = 6 + r.nextDouble() * 4;
		double lat = 46 + r.nextDouble() * 2;
		content.put("centroid", List.of(round(lon), round(lat)));
		var ring = new ArrayList<Object>(points);
		for (int i = 0; i < points; i++) {
			ring.add(List.of(round(lon + r.nextGaussian() * 0.1), round(lat + r.nextGaussian() * 0.1)));
		}
		content.put("geometry", Map.of("type", "Polygon", "coordinates", List.of(ring)));
		var line = new LinkedHashMap<String, Object>();
		line.put("type", "Place");
		line.put("content", List.of(content));
		return line;
	}

	/// Seven decimals, as OpenStreetMap coordinates have.
	static double round(double d) {
		return Math.round(d * 1e7) / 1e7;
	}

	/// A dump: a header, then places of various sizes.
	static byte[] geoLines(int places) {
		var r = new Random(7);
		var w = JsonWriter.ofBytes();
		w.value(Map.of("type", "NominatimDumpFile", "content", Map.of("version", "0.1.0")));
		for (int i = 0; i < places; i++) {
			w.value(place(r, i + 1, r.nextInt(10) == 0 ? 2_000 + r.nextInt(3_000) : 4 + r.nextInt(60)));
		}
		return w.toBytes();
	}

}
