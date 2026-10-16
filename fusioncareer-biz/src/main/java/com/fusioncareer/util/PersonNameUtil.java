package com.fusioncareer.util;

import java.text.Normalizer;
import java.util.Locale;

/** 姓名展示与 UIS 同步共用的规则，账号标识不能作为姓名。 */
public final class PersonNameUtil {
    private PersonNameUtil() { }

    public static String readName(String readStudentId, String... readNames) {
        for (String readName : readNames) {
            if (readName == null) continue;
            String readKey = normalizeKey(readName);
            if (!readKey.isEmpty() && !readKey.equals("null") && !readKey.equals("undefined")
                    && !readKey.matches("\\p{Nd}+") && !readKey.equals(normalizeKey(readStudentId))) {
                return readName.strip();
            }
        }
        return null;
    }

    private static String normalizeKey(String readValue) {
        return readValue == null ? "" : Normalizer.normalize(readValue, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
    }
}
