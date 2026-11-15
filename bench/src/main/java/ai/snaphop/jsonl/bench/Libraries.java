package ai.snaphop.jsonl.bench;

import com.alibaba.fastjson2.JSONReader;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;

import io.avaje.json.stream.JsonStream;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import org.apache.fory.json.ForyJson;
import tools.jackson.databind.json.JsonMapper;

/// Each library set up once, as an application would.
final class Libraries {

	private Libraries() {
	}

	/// Maps and lists, with nulls written.
	static final JsonType<Object> AVAJE = Jsonb.builder().serializeNulls(true).build().type(Object.class);

	/// As geon's `DumpReader`.
	static final JsonStream AVAJE_STREAM = JsonStream.builder().build();

	static final JsonMapper JACKSON = JsonMapper.builder().build();

	static final DslJson<Object> DSL = new DslJson<>(Settings.withRuntime().includeServiceLoader());

	static final JSONReader.Feature[] FASTJSON_FEATURES = { JSONReader.Feature.UseDoubleForDecimals };

	static final ForyJson FORY = ForyJson.builder().build();

}
