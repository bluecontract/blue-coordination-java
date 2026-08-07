package blue.coordination.examples.support;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Small deterministic YAML-text utilities; no Blue document is built imperatively. */
public final class MyOsDemoYaml {

    private static final Pattern INITIAL_BLUE_ID = Pattern.compile(
            "\\{\\{initialBlueId:([A-Za-z0-9_-]+)}}");

    private MyOsDemoYaml() {
    }

    public static String resolveInitialBlueIds(
            String source,
            Map<String, String> initialBlueIds) {
        Matcher matcher = INITIAL_BLUE_ID.matcher(
                Objects.requireNonNull(source, "source"));
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String blueId = initialBlueIds.get(key);
            if (blueId == null) {
                throw new IllegalStateException(
                        "Unknown or forward initial-document reference: " + key);
            }
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(blueId));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return Objects.requireNonNull(value, "value")
                .lines()
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }
}
