package campus.support;

import static campus.exception.BusinessException.fail;

public final class TextValidation {
    private TextValidation() {}

    public static void text(String value, int min, int max, String code) {
        if (value == null
                || value.codePoints()
                        .allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))
                || value.codePointCount(0, value.length()) < min
                || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(cp -> cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF))) {
            throw fail(400, code);
        }
    }
}
