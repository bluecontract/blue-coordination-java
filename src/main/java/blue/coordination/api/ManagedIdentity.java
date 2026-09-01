package blue.coordination.api;

import blue.language.model.value.BlueNumbers;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical domain-separated identity support for managed-epoch evidence. */
final class ManagedIdentity {
    private static final String SHA_256_PREFIX = "sha256:";
    private static final long MAX_SAFE_INTEGER =
            BlueNumbers.MAX_INTEROPERABLE_INTEGER.longValueExact();
    private static final ObjectMapper IDENTITY_MAPPER = identityMapper();

    private ManagedIdentity() {
    }

    static String identify(String domain, Map<String, ?> value) {
        LinkedHashMap<String, Object> constructor = new LinkedHashMap<>();
        constructor.put("domain", requireText(domain, "domain"));
        constructor.put("value", Objects.requireNonNull(value, "value"));
        Object portable = copyPortableValue(constructor, "identity");
        byte[] canonical = canonicalBytes(portable);
        try {
            return SHA_256_PREFIX + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String verify(
            String asserted,
            String domain,
            Map<String, ?> value,
            String label) {
        String checked = requireSha256(asserted, label);
        String computed = identify(domain, value);
        if (!checked.equals(computed)) {
            throw new IllegalArgumentException(
                    label + " does not identify the supplied evidence");
        }
        return checked;
    }

    static String requireSha256(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (!checked.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase sha256 identity");
        }
        return checked;
    }

    static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        requirePortableText(checked, label);
        return checked;
    }

    static long requireSafeInteger(long value, String label) {
        if (value < 0L || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    label + " must be a non-negative JSON safe integer");
        }
        return value;
    }

    static long requireSafeEpoch(long value, String label) {
        if (value < -1L || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    label + " must be -1 or a non-negative JSON safe integer");
        }
        return value;
    }

    static Map<String, Object> fields(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Canonical fields require name/value pairs");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            String name = requireText(
                    (String) namesAndValues[index], "field name");
            if (result.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Canonical field is repeated: " + name);
            }
            result.put(name, namesAndValues[index + 1]);
        }
        return result;
    }

    static List<Object> canonicalOrderComponents(List<Object> components) {
        ArrayList<Object> values = new ArrayList<>();
        for (Object component : components) {
            if (!(component instanceof String)
                    && !(component instanceof BigInteger)
                    && !(component instanceof Byte)
                    && !(component instanceof Short)
                    && !(component instanceof Integer)
                    && !(component instanceof Long)) {
                throw new IllegalArgumentException(
                        "Unsupported external-order component");
            }
            values.add(component);
        }
        return List.copyOf(values);
    }

    private static Object copyPortableValue(Object value, String label) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return requirePortableText(text, label);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long integer = ((Number) value).longValue();
            if (integer < -MAX_SAFE_INTEGER || integer > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException(
                        label + " must be a JSON safe integer");
            }
            return Long.valueOf(integer);
        }
        if (value instanceof BigInteger integer) {
            BigInteger maximum = BigInteger.valueOf(MAX_SAFE_INTEGER);
            if (integer.compareTo(maximum.negate()) < 0
                    || integer.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(
                        label + " must be a JSON safe integer");
            }
            return Long.valueOf(integer.longValue());
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> copy = new ArrayList<>();
            int index = 0;
            for (Object item : collection) {
                copy.add(copyPortableValue(item, label + "[" + index++ + "]"));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            label + " has a non-Text object key");
                }
                String portableKey = requirePortableText(key, label + " key");
                copy.put(portableKey, copyPortableValue(
                        entry.getValue(), label + "." + portableKey));
            }
            return copy;
        }
        throw new IllegalArgumentException(
                label + " has unsupported canonical value "
                        + value.getClass().getName());
    }

    private static String requirePortableText(String value, String label) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(label + " must be NFC-normalized");
        }
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            label + " contains an unpaired Unicode surrogate");
                }
                codePoint = Character.toCodePoint(first, value.charAt(index + 1));
            } else if (Character.isLowSurrogate(first)) {
                throw new IllegalArgumentException(
                        label + " contains an unpaired Unicode surrogate");
            } else {
                codePoint = first;
            }
            if (codePoint == 0) {
                throw new IllegalArgumentException(
                        label + " must not contain U+0000");
            }
            index += Character.charCount(codePoint);
        }
        return value;
    }

    private static byte[] canonicalBytes(Object value) {
        try {
            return new JsonCanonicalizer(
                    IDENTITY_MAPPER.writeValueAsString(value)).getEncodedUTF8();
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize managed-epoch identity", exception);
        }
    }

    private static ObjectMapper identityMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return mapper;
    }
}
