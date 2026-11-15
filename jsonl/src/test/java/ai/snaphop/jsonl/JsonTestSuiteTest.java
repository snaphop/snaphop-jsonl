package ai.snaphop.jsonl;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/// [JSONTestSuite](https://github.com/nst/JSONTestSuite)'s parsing cases: `y_` must
/// parse and `n_` must not, from bytes and from a stream. `i_` cases may go either way,
/// but must not fail other than with a [JsonException].
class JsonTestSuiteTest {

	@TestFactory
	Stream<DynamicTest> cases() throws IOException, URISyntaxException {
		Path dir = Path.of(JsonTestSuiteTest.class.getResource("/JSONTestSuite").toURI());
		List<Path> files;
		try (var list = Files.list(dir)) {
			files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		return files.stream().map(p -> {
			String name = p.getFileName().toString();
			return DynamicTest.dynamicTest(name, () -> {
				byte[] bytes = read(p);
				switch (name.charAt(0)) {
					case 'y' -> {
						Object fromBytes = Json.read(bytes);
						Object fromStream = Json.read(new JsonReaderTest.Trickle(bytes, null));
						org.junit.jupiter.api.Assertions.assertEquals(fromBytes, fromStream);
					}
					case 'n' -> {
						assertThrows(JsonException.class, () -> Json.read(bytes));
						assertThrows(JsonException.class, () -> Json.read(new JsonReaderTest.Trickle(bytes, null)));
					}
					default -> {
						try {
							Json.read(bytes);
						}
						catch (JsonException e) {
							// either way
						}
					}
				}
			});
		});
	}

	private static byte[] read(Path p) {
		try {
			return Files.readAllBytes(p);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
