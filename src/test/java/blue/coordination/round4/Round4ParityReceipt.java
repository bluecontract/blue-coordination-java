package blue.coordination.round4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Writes one deterministic same-run receipt after an oracle campaign passes. */
public final class Round4ParityReceipt {
    private static final String OUTPUT_PROPERTY =
            "coordination.round4.parityEvidenceDir";

    private Round4ParityReceipt() { }

    public static void write(
            String category,
            long comparisons,
            long mismatches) {
        String output = System.getProperty(OUTPUT_PROPERTY);
        if (output == null || output.trim().isEmpty()) return;
        String checkedCategory = Objects.requireNonNull(
                category, "category");
        if (!checkedCategory.matches(
                "(eventShape|sparseRoot|planning|projection|transition)"
                        + "Comparisons")) {
            throw new IllegalArgumentException(
                    "Invalid parity category: " + checkedCategory);
        }
        if (comparisons < 0L || mismatches < 0L
                || mismatches > comparisons) {
            throw new IllegalArgumentException(
                    "Invalid parity comparison totals");
        }
        Path directory = Paths.get(output).toAbsolutePath().normalize();
        Path destination = directory.resolve(checkedCategory + ".json");
        Path temporary = directory.resolve(
                checkedCategory + ".json.tmp");
        String json = "{\n"
                + "  \"schema\": "
                + "\"blue-coordination/myos-round4-parity-receipt/1.0\",\n"
                + "  \"category\": \"" + checkedCategory + "\",\n"
                + "  \"comparisons\": " + comparisons + ",\n"
                + "  \"mismatches\": " + mismatches + "\n"
                + "}\n";
        try {
            Files.createDirectories(directory);
            Files.write(
                    temporary,
                    json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not write Round-4 parity receipt "
                            + destination,
                    failure);
        }
    }
}
