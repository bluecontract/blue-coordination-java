package blue.coordination.processor;

import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.utils.BlueIdCalculator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal canonical scalar-map codec for subscription projection values. */
final class CoordinationSubscriptionSerialization {
    private CoordinationSubscriptionSerialization() {
    }

    static String digest(Map<String, Object> canonical) {
        return BlueIdCalculator.INSTANCE.calculate(canonical);
    }

    /**
     * Recursively copies a canonical persistence value into unmodifiable
     * list/map containers.
     *
     * <p>The public snapshot codec must not expose a mutable nested container:
     * a host may safely hand the returned value to another component without
     * allowing that component to rewrite the persistence evidence in place.</p>
     */
    static Map<String, Object> immutableMap(
            Map<String, ?> supplied) {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry
                : supplied.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException(
                        "Canonical persistence map contains "
                                + "a null key");
            }
            result.put(
                    key,
                    immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    static void requireFields(
            Map<String, ?> map,
            String objectName,
            String[] required,
            String... optional) {
        Set<String> requiredFields =
                new LinkedHashSet<String>(
                        Arrays.asList(required));
        Set<String> allowed =
                new LinkedHashSet<String>(
                        requiredFields);
        allowed.addAll(Arrays.asList(optional));
        for (Map.Entry<?, ?> entry
                : map.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String)) {
                throw invalid(
                        objectName,
                        "contains a non-Text field name");
            }
            if (!allowed.contains(key)) {
                throw invalid(
                        objectName,
                        "contains unknown field '" + key + "'");
            }
            if (entry.getValue() == null) {
                throw invalid(
                        (String) key,
                        "must be omitted rather than null");
            }
        }
        for (String field : requiredFields) {
            if (!map.containsKey(field)) {
                throw invalid(
                        objectName,
                        "is missing required field '"
                                + field + "'");
            }
        }
    }

    static List<Object> orderKeyToList(
            ExternalOrderKey orderKey) {
        return Collections.unmodifiableList(
                new ArrayList<Object>(
                        orderKey.components()));
    }

    static ExternalOrderKey optionalOrderKey(
            Map<String, ?> map,
            String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw invalid(key, "must be a list");
        }
        List<?> supplied = (List<?>) value;
        List<Object> components =
                new ArrayList<Object>(supplied.size());
        for (Object component : supplied) {
            if (!(component instanceof String)
                    && !(component instanceof Number)) {
                throw invalid(
                        key,
                        "contains a non Text/Integer component");
            }
            components.add(canonicalInteger(component));
        }
        return ExternalOrderKey.of(components);
    }

    static String text(
            Map<String, ?> map,
            String key) {
        Object value = map.get(key);
        if (!(value instanceof String)
                || ((String) value).isEmpty()) {
            throw invalid(key, "must be non-empty Text");
        }
        return (String) value;
    }

    static int integer(
            Map<String, ?> map,
            String key) {
        long value = requiredLong(map, key);
        if (value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            throw invalid(key, "is outside Integer range");
        }
        return (int) value;
    }

    static long requiredLong(
            Map<String, ?> map,
            String key) {
        Long value = optionalLong(map, key);
        if (value == null) {
            throw invalid(key, "must be an Integer");
        }
        return value.longValue();
    }

    static Long optionalLong(
            Map<String, ?> map,
            String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        BigInteger integer = toBigInteger(value, key);
        if (integer.compareTo(
                BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || integer.compareTo(
                BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw invalid(key, "is outside Long range");
        }
        return Long.valueOf(integer.longValue());
    }

    static boolean bool(
            Map<String, ?> map,
            String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean)) {
            throw invalid(key, "must be Boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    static List<String> textList(
            Map<String, ?> map,
            String key) {
        Object value = map.get(key);
        if (!(value instanceof List)) {
            throw invalid(key, "must be a list");
        }
        List<String> result =
                new ArrayList<String>();
        for (Object element : (List<?>) value) {
            if (!(element instanceof String)
                    || ((String) element).isEmpty()) {
                throw invalid(
                        key,
                        "contains a non-empty Text violation");
            }
            result.add((String) element);
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, String> textMap(
            Map<String, ?> map,
            String key) {
        Map<String, ?> supplied = map(map, key);
        Map<String, String> result =
                new LinkedHashMap<String, String>();
        for (Map.Entry<String, ?> entry
                : supplied.entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() == null
                    || entry.getKey().isEmpty()
                    || !(value instanceof String)
                    || ((String) value).isEmpty()) {
                throw invalid(key, "contains invalid Text");
            }
            result.put(entry.getKey(), (String) value);
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    static Map<String, ?> map(
            Map<String, ?> owner,
            String key) {
        Object value = owner.get(key);
        if (!(value instanceof Map)) {
            throw invalid(key, "must be an object");
        }
        Map<?, ?> supplied = (Map<?, ?>) value;
        for (Object suppliedKey : supplied.keySet()) {
            if (!(suppliedKey instanceof String)) {
                throw invalid(key, "contains a non-Text key");
            }
        }
        return (Map<String, ?>) supplied;
    }

    static List<Map<String, ?>> mapList(
            Map<String, ?> owner,
            String key) {
        Object value = owner.get(key);
        if (!(value instanceof List)) {
            throw invalid(key, "must be a list");
        }
        List<Map<String, ?>> result =
                new ArrayList<Map<String, ?>>();
        for (Object element : (List<?>) value) {
            if (!(element instanceof Map)) {
                throw invalid(key, "contains a non-object");
            }
            Map<?, ?> supplied = (Map<?, ?>) element;
            for (Object suppliedKey
                    : supplied.keySet()) {
                if (!(suppliedKey instanceof String)) {
                    throw invalid(
                            key,
                            "contains an object with "
                                    + "a non-Text key");
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, ?> entry =
                    (Map<String, ?>) supplied;
            result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, Object> dependencyToMap(
            ExternalChannelDependencySnapshot dependency) {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        result.put(
                "intrinsicNodeBlueIds",
                dependency.intrinsicNodeBlueIds());
        List<Map<String, Object>> entries =
                new ArrayList<Map<String, Object>>();
        for (ExternalChannelDependencySnapshot.Entry entry
                : dependency.entries()) {
            Map<String, Object> encoded =
                    new LinkedHashMap<String, Object>();
            encoded.put("channelKey", entry.channelKey());
            encoded.put("order", entry.order());
            encoded.put(
                    "effectiveTypeBlueId",
                    entry.effectiveTypeBlueId());
            encoded.put(
                    "sourceContributionNodeBlueIds",
                    entry.sourceContributionNodeBlueIds());
            encoded.put(
                    "deterministicDependencyNodeBlueIds",
                    entry.deterministicDependencyNodeBlueIds());
            encoded.put(
                    "checkpointDomainBlueId",
                    entry.checkpointDomainBlueId());
            entries.add(encoded);
        }
        result.put("entries", entries);

        List<Map<String, Object>> families =
                new ArrayList<Map<String, Object>>();
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : dependency.typeFamilies()) {
            Map<String, Object> encoded =
                    new LinkedHashMap<String, Object>();
            encoded.put(
                    "excludingChannelKey",
                    family.excludingChannelKey());
            encoded.put(
                    "effectiveTypeBlueId",
                    family.effectiveTypeBlueId());
            encoded.put(
                    "matchMode",
                    family.matchMode().name());
            List<Map<String, Object>> members =
                    new ArrayList<Map<String, Object>>();
            for (ExternalChannelDependencySnapshot.Member member
                    : family.members()) {
                Map<String, Object> encodedMember =
                        new LinkedHashMap<String, Object>();
                encodedMember.put(
                        "channelKey",
                        member.channelKey());
                encodedMember.put(
                        "order",
                        member.order());
                encodedMember.put(
                        "effectiveTypeBlueId",
                        member.effectiveTypeBlueId());
                encodedMember.put(
                        "sourceContributionNodeBlueIds",
                        member.sourceContributionNodeBlueIds());
                encodedMember.put(
                        "deterministicDependencyNodeBlueIds",
                        member.deterministicDependencyNodeBlueIds());
                members.add(encodedMember);
            }
            encoded.put("members", members);
            families.add(encoded);
        }
        result.put("typeFamilies", families);
        result.put(
                "wholeSameScopeExternalSurface",
                dependency.wholeSameScopeExternalSurface());

        List<Map<String, Object>> channels =
                new ArrayList<Map<String, Object>>();
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : dependency.channelEntries()) {
            Map<String, Object> encoded =
                    new LinkedHashMap<String, Object>();
            encoded.put("channelKey", entry.channelKey());
            encoded.put("order", entry.order());
            encoded.put(
                    "effectiveTypeBlueId",
                    entry.effectiveTypeBlueId());
            encoded.put("role", entry.role());
            encoded.put(
                    "sourceContributionNodeBlueIds",
                    entry.sourceContributionNodeBlueIds());
            encoded.put(
                    "deterministicDependencyNodeBlueIds",
                    entry.deterministicDependencyNodeBlueIds());
            encoded.put(
                    "headerIdentityBlueId",
                    entry.headerIdentityBlueId());
            channels.add(encoded);
        }
        result.put("channelEntries", channels);
        result.put(
                "wholeSameScopeChannelCatalog",
                dependency.wholeSameScopeChannelCatalog());
        result.put(
                "channelCatalogContractKeys",
                dependency.channelCatalogContractKeys());
        return immutableMap(result);
    }

    static ExternalChannelDependencySnapshot dependencyFromMap(
            Map<String, ?> map) {
        requireFields(
                map,
                "dependencies",
                new String[] {
                        "intrinsicNodeBlueIds",
                        "entries",
                        "typeFamilies",
                        "wholeSameScopeExternalSurface",
                        "channelEntries",
                        "wholeSameScopeChannelCatalog",
                        "channelCatalogContractKeys"
                });
        List<ExternalChannelDependencySnapshot.Entry> entries =
                new ArrayList<ExternalChannelDependencySnapshot.Entry>();
        for (Map<String, ?> encoded
                : mapList(map, "entries")) {
            requireFields(
                    encoded,
                    "dependency entry",
                    new String[] {
                            "channelKey",
                            "order",
                            "effectiveTypeBlueId",
                            "sourceContributionNodeBlueIds",
                            "deterministicDependencyNodeBlueIds",
                            "checkpointDomainBlueId"
                    });
            entries.add(
                    new ExternalChannelDependencySnapshot.Entry(
                            text(encoded, "channelKey"),
                            integer(encoded, "order"),
                            text(
                                    encoded,
                                    "effectiveTypeBlueId"),
                            textList(
                                    encoded,
                                    "sourceContributionNodeBlueIds"),
                            textList(
                                    encoded,
                                    "deterministicDependencyNodeBlueIds"),
                            text(
                                    encoded,
                                    "checkpointDomainBlueId")));
        }

        List<ExternalChannelDependencySnapshot.TypeFamily> families =
                new ArrayList<
                        ExternalChannelDependencySnapshot.TypeFamily>();
        for (Map<String, ?> encoded
                : mapList(map, "typeFamilies")) {
            requireFields(
                    encoded,
                    "dependency type family",
                    new String[] {
                            "excludingChannelKey",
                            "effectiveTypeBlueId",
                            "matchMode",
                            "members"
                    });
            List<ExternalChannelDependencySnapshot.Member> members =
                    new ArrayList<
                            ExternalChannelDependencySnapshot.Member>();
            for (Map<String, ?> member
                    : mapList(encoded, "members")) {
                requireFields(
                        member,
                        "dependency type-family member",
                        new String[] {
                                "channelKey",
                                "order",
                                "effectiveTypeBlueId",
                                "sourceContributionNodeBlueIds",
                                "deterministicDependencyNodeBlueIds"
                        });
                members.add(
                        new ExternalChannelDependencySnapshot.Member(
                                text(member, "channelKey"),
                                integer(member, "order"),
                                text(
                                        member,
                                        "effectiveTypeBlueId"),
                                textList(
                                        member,
                                        "sourceContributionNodeBlueIds"),
                                textList(
                                        member,
                                        "deterministicDependencyNodeBlueIds")));
            }
            ExternalChannelDependencySnapshot.TypeMatchMode
                    mode;
            try {
                mode =
                        ExternalChannelDependencySnapshot
                                .TypeMatchMode.valueOf(
                                text(encoded, "matchMode"));
            } catch (IllegalArgumentException exception) {
                throw invalid(
                        "matchMode",
                        "is unsupported");
            }
            families.add(
                    new ExternalChannelDependencySnapshot.TypeFamily(
                            text(
                                    encoded,
                                    "excludingChannelKey"),
                            text(
                                    encoded,
                                    "effectiveTypeBlueId"),
                            mode,
                            members));
        }

        List<ExternalChannelDependencySnapshot.ChannelEntry>
                channels =
                new ArrayList<
                        ExternalChannelDependencySnapshot.ChannelEntry>();
        for (Map<String, ?> encoded
                : mapList(map, "channelEntries")) {
            requireFields(
                    encoded,
                    "dependency Channel entry",
                    new String[] {
                            "channelKey",
                            "order",
                            "effectiveTypeBlueId",
                            "role",
                            "sourceContributionNodeBlueIds",
                            "deterministicDependencyNodeBlueIds",
                            "headerIdentityBlueId"
                    });
            channels.add(
                    new ExternalChannelDependencySnapshot.ChannelEntry(
                            text(encoded, "channelKey"),
                            integer(encoded, "order"),
                            text(
                                    encoded,
                                    "effectiveTypeBlueId"),
                            text(encoded, "role"),
                            textList(
                                    encoded,
                                    "sourceContributionNodeBlueIds"),
                            textList(
                                    encoded,
                                    "deterministicDependencyNodeBlueIds"),
                            text(
                                    encoded,
                                    "headerIdentityBlueId")));
        }
        return new ExternalChannelDependencySnapshot(
                textList(map, "intrinsicNodeBlueIds"),
                entries,
                families,
                bool(
                        map,
                        "wholeSameScopeExternalSurface"),
                channels,
                bool(
                        map,
                        "wholeSameScopeChannelCatalog"),
                textList(
                        map,
                        "channelCatalogContractKeys"));
    }

    private static Object immutableValue(
            Object value) {
        if (value instanceof Map) {
            Map<?, ?> supplied = (Map<?, ?>) value;
            Map<String, Object> copy =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry
                    : supplied.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            "Canonical persistence map contains "
                                    + "a non-Text key");
                }
                copy.put(
                        (String) entry.getKey(),
                        immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List) {
            List<Object> copy =
                    new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                copy.add(immutableValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof BigInteger
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        throw new IllegalArgumentException(
                "Unsupported canonical persistence value: "
                        + value.getClass().getName());
    }

    private static Object canonicalInteger(Object value) {
        if (value instanceof String) {
            return value;
        }
        return toBigInteger(value, "order key");
    }

    private static BigInteger toBigInteger(
            Object value,
            String key) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return BigInteger.valueOf(
                    ((Number) value).longValue());
        }
        throw invalid(key, "must be an Integer");
    }

    private static IllegalArgumentException invalid(
            String key,
            String message) {
        return new IllegalArgumentException(
                "Persisted subscription field '"
                        + key + "' " + message);
    }
}
