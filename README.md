# jsonl

Small, stable JSON and JSON lines for Java, written to replace avaje-jsonb in
snaphop-geon and an application's JSON lines backups. There is no binding, no reflection and no `Unsafe`,
and no limit on the size of a string or a value beyond memory unless a reader is given
one. For JSON from anyone else, give it one: see [Untrusted JSON](#untrusted-json).

* `JsonReader`: a pull parser over `byte[]`, `String` or an `InputStream`. It reads
  any number of top-level values, so JSON lines need no line splitting. It can also
  read a value as a tree, as raw JSON (`readRaw`) or skip it.
* `JsonWriter`: writes UTF-8 to a stream or to memory. Top-level values go on lines
  of their own. It writes nulls, and `rawValue` is correct for non-ASCII text.
* `Json`: one value to or from maps, lists, `String`, `Long` (`BigInteger` past a
  `long`), `Double`, `Boolean` and `null`.

It is a 30 KB jar with `module com.snaphop.jsonl`, and its only dependency is
`requires static transitive org.jspecify`. Releases are published to SnapHop's
Maven repository:

```xml
<repositories>
  <repository>
    <id>snaphop-public-release</id>
    <url>https://maven.snaphop.com/release</url>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependency>
  <groupId>com.snaphop</groupId>
  <artifactId>snaphop-jsonl</artifactId>
  <version>0.2.0</version>
</dependency>
```

```java
// restore a gzipped JSON lines backup, a line at a time, at any size
try (var r = JsonReader.of(new GZIPInputStream(Files.newInputStream(file)))) {
	while (r.hasNext()) {
		Map<String, Object> line = Json.map(r.readValue());
	}
}

// geon: pull a dump without trees or line splitting
try (var r = JsonReader.of(System.in)) {
	while (r.hasNext()) {
		r.beginObject();
		while (r.hasNext()) {
			switch (r.nextName()) {
				case "geometry" -> geometry = r.readRaw(); // megabytes are fine
				case "importance" -> importance = r.nextDouble();
				default -> r.skipValue();
			}
		}
		r.endObject();
	}
}
```

Build with JDK 25 or later: `./mvnw install`. `.sdkmanrc` names the JDK, and the
library targets Java 25, as geon does. The source is under the
[Zero-Clause BSD](LICENSE) license.

## Untrusted JSON

Without limits, a reader trusts its input: right for your own backups and dumps,
wrong for a request body, a webhook, or anything else a stranger can write. There,
**set the limits, for security**. A few hostile bytes can otherwise cost minutes of
CPU or all the memory there is.

```java
try (var r = JsonReader.of(body).maxNumberLength(1000).maxStringLength(1_000_000)) {
	Object value = r.readValue();
}
```

| Limit | Default | Guards against | Suggested |
|---|---|---|---|
| `maxNumberLength(int)`, in characters | none | time: the JDK's `BigInteger` and `BigDecimal` parse in time that grows with the square of a number's length, so `readValue` or `nextBigDecimal` on a number of a million digits takes about 12 seconds, and so ten million about 20 minutes | 1000 (Jackson's default) |
| `maxStringLength(int)`, in UTF-8 bytes once unescaped, names included | none | memory: one string as long as a stream goes on | what your data needs; 1 MB is generous for most fields |
| Nesting | always 1000 (`JsonReader.MAX_DEPTH`) | the stack | |
| The input as a whole | yours to set | memory: a tree of many small values | an HTTP server's request body limit, or a bounded `InputStream` |

Each reader has its own limits. The checks stop once they find an over-limit token,
subject to stream buffer read-ahead. A refused number stays next in the pull API.
Close the reader after a string exceeds its limit.

`skipValue` and `readRaw` check bracket pairing and string escapes. Per-token limits
apply to typed reads and tree reads. Skipping uses bounded buffering, and `readRaw`
keeps the whole value. Use `JsonReader` with limits for untrusted JSON and bound the
input before reading raw values.

## Publishing

The library and its parent pom are published to SnapHop's Maven repository, as
snaphop-ical is. The benchmarks are never installed or published.

| Repository | Deployed to | Read from |
|---|---|---|
| Releases | `https://maven.snaphop.com/deploy/repository/public-release/` | `https://maven.snaphop.com/release` |
| Snapshots | `https://maven.snaphop.com/deploy/repository/public-snapshot/` | |

The deploy targets and their user names are in `.snaphop/settings.xml`. The password
comes from the `NEXUS_SNAPHOP_COM_PASSWORD` environment variable, which is a secret of
the GitHub repository.

* **A release:** run `bin/release.sh` on `main`, clean and in sync with
  `origin/main`. It does the following:
  1. sets the version to `X.Y.Z` and runs `verify` with the `release` profile;
  2. commits `Release X.Y.Z` and tags it `vX.Y.Z`;
  3. commits `Prepare X.Y.(Z+1)-SNAPSHOT`;
  4. pushes `main` and the tag.

  The tag runs the `Deploy to SnapHop` workflow, which builds, tests and deploys
  that version. `NO_PUSH=true` stops before pushing. The `release` profile refuses a
  `SNAPSHOT` version or dependency, and a build without `-s .snaphop/settings.xml`.
* **A snapshot:** run the `Deploy to SnapHop` workflow by hand on a branch.
* **From this machine**, as a fallback: `bin/deploy.sh`, with the password in the
  environment.

An agent does not release, push a tag or deploy unless asked to.

## Why not avaje-jsonb

Both projects hit avaje-json-core 3.16 problems, all from large streamed payloads.
See [avaje/avaje-jsonb#607](https://github.com/avaje/avaje-jsonb/issues/607) and
geon's `doc/dependency-issues.md`.

| Problem | Where it hit | Workaround today |
|---|---|---|
| Global 50,000-character string limit (`jsonb.parserMaxStringBuffer`), for `String`, `byte[]` and stream input alike | restoring a JSON lines backup: a file's bytes, as hex, in one line | the database parses each line instead of Java |
| The same limit on `readRaw()` from a stream | geon: geometries of planet dumps are megabytes | `geon-dump/Json.java` copies geometries token by token |
| `hasNextElement()` ends an array early when a string closes on the last byte of the 4096-byte buffer (fixed upstream by #597, after 3.16) | geon: Switzerland import failed at byte 3,666,782,212 | `geon-dump/Lines.java` splits lines and parses each from a `byte[]` |
| `rawValue(String)` writes each `char` as one byte, which corrupts non-ASCII text | geon: `extra` tags such as `official_name:zh` | `geon-photon/RawJson.java` reparses non-ASCII |
| Doubles aren't correctly rounded: `40.03362634200912` reads as `40.033626342009114` (found by `bench`'s `Check`) | Not noticed yet; affects round trips of 17-digit doubles | None |

The buffer-edge bug shows what goes wrong in this kind of code: a fast path that
reads to the end of the buffer without refilling, so a token that ends on the edge
looks like the end of the input. Here every scan of a string, a number or a skipped
value refills at the edge and grows the buffer when one token is longer than it. The
tests check this directly:

* every JSON value read from a stream that gives one byte per `read` (16-byte
  buffer), and in random chunks, compared with reading the same bytes whole;
* a document shifted across a 64-byte buffer one byte at a time, read with the pull
  API;
* avaje's own reproduction, 3 MB hex strings in JSON lines, and 4 MB raw values;
* all 283 `y_` and `n_` cases of [JSONTestSuite](https://github.com/nst/JSONTestSuite)
  read from bytes and one byte at a time; the 35 `i_` cases may only throw
  `JsonException`.

Writing this prototype hit a bug of exactly this kind. A number's start was taken
before the refill that moves it, and the byte-at-a-time test caught it the first time
it ran.

## Moving from avaje

| avaje | jsonl |
|---|---|
| `Jsonb.builder().serializeNulls(true).build().type(Object.class)`: `fromJson`, `toJson` | `Json.read`, `Json.write`, `Json.writeBytes` |
| `JsonStream.reader(...)` | `JsonReader.of(...)` |
| `currentToken()` | `peek()` |
| `beginObject()` / `hasNextField()` / `nextField()` | `beginObject()` / `hasNext()` / `nextName()` |
| `beginArray()` / `hasNextElement()` | `beginArray()` / `hasNext()` |
| `beginStream()` / `hasNextStreamElement()` | `hasNext()` outside any array or object |
| `isNullValue()` | `nextIfNull()` |
| `readString`, `readLong`, `readDouble`, `readBoolean`, `readDecimal` | `nextString`, `nextLong`, `nextDouble`, `nextBoolean`, `nextBigDecimal` |
| `readRaw()` | `readRaw()` or `readRawBytes()` |
| `JsonStream.bufferedWriterAsBytes()` | `JsonWriter.ofBytes()` |
| `serializeNulls(true)` | always: nulls are always written |
| `jsonb.parserMaxStringBuffer` | nothing to set |

There is no binding. A `@Json` class stays with avaje, or writes itself through
`JsonWritable`.

Where the two differ (checked with avaje 3.16):

| | avaje | jsonl |
|---|---|---|
| Read `1.0`, `1e3` | `Long` 1, 1000 | `Double` 1.0, 1000.0: a fraction or exponent makes a double, so an `instanceof Long` check on a value a client may write as `5.0` changes |
| Read `12345678901234567890` | `Long` 9223372036854775807, silently clamped | `BigInteger` |
| Read `40.03362634200912` | 40.033626342009114 | 40.03362634200912, correctly rounded |
| Read `{"a":1} x` | `{a=1}`, the trailing text ignored | an error naming the line and column; `JsonReader` reads one value after another, as JSON lines has them |
| Read `{"a":1,"a":2}` | an error | `{a=2}`: the last wins, as in PostgreSQL's jsonb and Dart's `jsonDecode` |
| Read a tab inside a string | the tab | an error: JSON wants it escaped |
| Read `"\ud800"`, a lone surrogate | `?` | U+FFFD |
| Write a lone surrogate | an error | U+FFFD |
| Write `NaN` or an infinity | the string `"NaN"` | an error: JSON has no such numbers |
| Write `-0.0` | `0.0` | `-0.0` |
| Write a `UUID`, an `Instant`, an `Optional` | a string, a string, `null` | an error: convert it first, so its form is your choice, not the library's |

## Testing

`./mvnw install` runs 388 tests in about 5 seconds:

* **Conformance**: every [JSONTestSuite](https://github.com/nst/JSONTestSuite) case,
  from bytes and one byte at a time.
* **Buffer edges**: every value read whole, one byte per `read` and in random
  chunks, and written through every buffer size from 16 to 90 bytes. Raw values of
  every length are mixed in, so each kind of write meets a full buffer.
* **Fuzzing** (`FuzzTest`), with Jackson 3 as the oracle:
  - generated documents with random formatting and nasty strings and numbers, read
    whole and from random chunked streams;
  - documents damaged by deleting, inserting, flipping, truncating and duplicating
    bytes. jsonl and Jackson must agree on whether each is JSON, and on its value.
    jsonl must read the same value, or fail with the same message at the same place,
    from bytes, chunked streams and the pull API, and `skipValue` must accept
    whatever the tree accepts;
  - random mixes of the pull API, `readRaw` and `skipValue` must agree with the
    tree;
  - number text must agree with `BigDecimal`, `Double.parseDouble` and `long`.

  - properties of any generated document (`PropertyTest`): indenting changes only
    whitespace outside strings; values written as JSON lines are one compact line
    each and read back in order, from bytes and chunked streams; `maxStringLength`
    and `maxNumberLength` refuse exactly the documents with a string or number past
    them; and an error's line and column are where its offset is.

  `-Dfuzz.iterations=200000` runs 1.4 million cases in about a minute. Runs with
  three different seeds were clean, apart from where Jackson is lenient (it takes a
  NUL byte as the end of the input) or strict on purpose (it rejects escaped lone
  surrogates in names).
* **Mutation testing** with PIT (`STRONGER` mutators):
  `./mvnw -pl jsonl -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage`.
  The score went from 78% to **87% killed (1,017 of 1,164), with 88% test strength
  and 99% line coverage**, after adding tests for what survived. What still
  survives changes speed but not results:
  - the name cache, buffer growth and scratch reuse;
  - the SWAR switch and the bounds of its word loop;
  - the short and long string thresholds;
  - the number fast paths, which fall back to `Double.parseDouble` or `BigDecimal`.

  Four mutants are only reachable with a 2 GB buffer.

What testing found:

* A number's start taken before a refill that moves it. This is the same class of
  bug as avaje's.
* The tree reader allowed one less level of nesting than the pull API, and
  `skipValue` counted nesting from where it started.
* The writer wrote a lone surrogate as `?` (what `String.getBytes` does) while the
  reader made it U+FFFD; now both use U+FFFD.

## Alternatives considered

| | No `Unsafe` | module-info | Size | Limits | Notes |
|---|---|---|---|---|---|
| avaje-jsonb 3.16 | yes | yes | ~260 KB with json-core | global 50,000-char strings | the bugs above |
| Jackson 3 (`jackson-core` alone) | yes | yes | ~600 KB core, ~2 MB with databind | `StreamReadConstraints`, per factory, default 100 M chars per string | stable and well tested. Maps and lists need databind or ~60 lines of our own over `JsonParser` |
| dsl-json 2 | yes | yes | ~500 KB | | fast; untyped `Object` and the runtime are awkward |
| fastjson2, Apache Fory JSON | **no** | | | | measured only as a ceiling |
| Gson `JsonReader` | yes | yes | ~310 KB | | reads `char`s through a `Reader`; slower on bytes |
| this | yes | yes | 30 KB | nesting depth (1000); number and string length, per reader, if asked | about 2,250 lines with their docs, and 2,000 of tests; we own it |

If writing our own isn't wanted, Jackson 3's `jackson-core` with a small tree adapter
is the stable choice.

## Design

* **One buffer.** For a stream, a 16 KB buffer that doubles only while a single
  token or raw value is longer than half of it. A `byte[]` input is read in place,
  without copying.
* **Strings.** The reader scans for `"`, `\` and control bytes 8 bytes at a time
  (SWAR, through a `VarHandle` view of the `byte[]`, a standard API). It then builds
  the `String` with `new String(bytes, UTF_8)`, which the JDK vectorizes for ASCII.
  Escapes take a slower path through a scratch buffer.
  `-Dcom.snaphop.jsonl.noswar=true` turns the word scan off, for comparison.
* **Names.** A small shared cache, so a name repeated across a dump's lines is one
  `String`.
* **Numbers.** Validated against the JSON grammar. Up to 18 digits become a `long`
  directly. A double with a mantissa below 2^53 and a power of ten up to 22 takes
  Clinger's exact fast path, which covers OpenStreetMap coordinates. Anything else
  goes through `Double.parseDouble`, so doubles are always correctly rounded.
* **Errors.** They name the line, column and offset. JSON can't hold a raw newline
  inside a token, so counting lines is nearly free and errors in JSON lines say
  which line.
* **Writer.** Strings of 64 or more characters are encoded with `String.getBytes`
  and copied between the bytes that need escapes, found with the same scan. Shorter
  ones are written a `char` at a time. `long`s are written without allocating;
  doubles use `Double.toString` (shortest, JDK 19+).

Deliberate leniencies: invalid UTF-8 inside strings and escaped lone surrogates
become U+FFFD when read, and the writer writes a lone surrogate in a Java string as
U+FFFD too, not as the `?` that `String.getBytes` gives. `skipValue` and `readRaw` check brackets and strings, but not what's between
them.

## Benchmarks

`bench` is a JMH suite adapted from
[fabienrenaud/java-json-benchmark](https://github.com/fabienrenaud/java-json-benchmark).
It uses the same `Users` payload, but as maps and lists rather than bound classes,
since neither project binds. It adds payloads shaped like our own data.
`Check` first confirms that every library reads and writes every payload to the
same values.

```sh
./mvnw install -DskipTests
java -cp bench/target/benchmarks.jar com.snaphop.jsonl.bench.Check
java -Djava.io.tmpdir=$PWD/target -jar bench/target/benchmarks.jar
```

(The `tmpdir` keeps JMH's lock out of the shared `/tmp`.)

Results on this machine (aarch64, 20 cores, JDK 27 Temurin; one fork, 5 × 2 s
after 3 × 2 s warmup). The machine is shared, so allow about ±5%. Lower is better.
The last column is avaje's time over jsonl's.

| Benchmark | Payload | jsonl | avaje | Jackson 3 | dsl-json | fastjson2* | Fory* | avaje / jsonl |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| Tree read, µs | users-1k | 2.3 | 2.6 | 3.3 | 2.2 | 1.6 | 1.8 | 1.11× |
| | users-100k | 105 | 142 | 161 | 111 | 87 | 96 | 1.36× |
| | users-1m | 1,068 | 1,368 | 1,634 | 1,109 | 906 | 982 | 1.28× |
| | i18n-100k | 139 | 171 | 173 | 138 | 142 | 127 | 1.23× |
| | export-1m | 128 | 273 | 365 | 269 | 132 | 85 | 2.14× |
| | geo-1m | 766 | 1,865 | 1,893 | 918 | 1,217 | 2,837 | 2.43× |
| Tree write, µs | users-1k | 2.8 | 2.7 | 2.3 | 2.3 | 1.5 | 1.7 | 0.99× |
| | users-100k | 121 | 145 | 99 | 115 | 76 | 83 | 1.20× |
| | users-1m | 1,203 | 1,397 | 1,071 | 1,246 | 842 | 819 | 1.16× |
| | i18n-100k | 138 | 148 | 133 | 117 | 82 | 119 | 1.07× |
| | export-1m | 282 | 497 | 579 | 512 | 114 | 74 | 1.76× |
| | geo-1m | 1,960 | 3,085 | 2,302 | 2,573 | 2,405 | 1,700 | 1.57× |
| JSON lines from a stream, ms | 20,000 events + 4 MB of files | 17.1 | 37.2 | 21.7 | | 31.0 | | 2.17× |
| Pull, token by token, ms | geon dump, 5,000 places | 71.0 | 79.6 | 141.5 | | | | 1.12× |

\* uses `sun.misc.Unsafe`; shown only as a ceiling.

* Payloads: `users-*` is java-json-benchmark's model. `i18n-100k` is the same with
  non-ASCII and escaped text. `export-1m` is one backup line holding 500 KB of file
  bytes as hex. `geo-1m` is a Photon dump place with a
  25,000-position polygon.
* avaje runs with `-Djsonb.parserMaxStringBuffer=100000000`. With its default,
  `export-1m` and the JSON lines benchmark fail.
* avaje's JSON lines read uses `BufferedReader.readLine` and a parse per line, as
  the backup restore did. Its pull read splits lines and parses each from a `byte[]`, as
  geon does, since 3.16's stream reader has the buffer-edge bug.
* jsonl is at least as fast as avaje everywhere. Writing users-1k is a tie, and
  Jackson writes small and medium trees about 15% faster.
* `-Dcom.snaphop.jsonl.noswar=true` turns the 8-byte string scan off. Short strings
  then scan a byte at a time as now, and long ones (export-1m) take about 1.7× as
  long.

Raw JMH output is in `results/final.json`.
