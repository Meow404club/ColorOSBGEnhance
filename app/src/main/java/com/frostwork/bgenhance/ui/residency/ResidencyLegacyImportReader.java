package com.frostwork.bgenhance.ui.residency;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads a bounded legacy policy file without accepting replacement characters. */
final class ResidencyLegacyImportReader {
    static final int MAX_BYTES = 512 * 1024;

    enum Failure {
        TOO_LARGE,
        INVALID_UTF8
    }

    static final class ReadException extends IOException {
        private final Failure failure;

        ReadException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    private ResidencyLegacyImportReader() {}

    static String read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new ReadException(Failure.TOO_LARGE);
            output.write(buffer, 0, read);
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray()))
                    .toString();
            return decoded.startsWith("\ufeff") ? decoded.substring(1) : decoded;
        } catch (CharacterCodingException exception) {
            throw new ReadException(Failure.INVALID_UTF8);
        }
    }
}
