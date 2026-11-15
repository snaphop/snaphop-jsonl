package ai.snaphop.jsonl;

import java.io.Serial;

/// Invalid JSON, a value of the wrong type, or a failure of the underlying stream.
///
/// When reading, the message says where: the line, the column (in bytes) and the
/// offset from the start of the input.
public final class JsonException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/// @param message what went wrong.
	public JsonException(String message) {
		super(message);
	}

	/// @param message what went wrong.
	/// @param cause the underlying failure.
	public JsonException(String message, Throwable cause) {
		super(message, cause);
	}

}
