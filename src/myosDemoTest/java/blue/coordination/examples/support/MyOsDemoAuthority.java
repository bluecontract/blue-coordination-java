package blue.coordination.examples.support;

import java.util.Objects;

/** Exact Mandate authority carried by an agent-authored Timeline Entry. */
public record MyOsDemoAuthority(
        MyOsDemoActor authorityHolder,
        String initialMandateDocumentBlueId) {

    public MyOsDemoAuthority {
        Objects.requireNonNull(authorityHolder, "authorityHolder");
        Objects.requireNonNull(
                initialMandateDocumentBlueId,
                "initialMandateDocumentBlueId");
    }

    String toYaml(int spaces) {
        String indent = " ".repeat(spaces);
        String actor = MyOsDemoYaml.indent(
                authorityHolder.toYaml(0).stripTrailing(), spaces + 2);
        return """
                %stype: Mandate/Mandate Authority
                %sactor:
                %s
                %sinitialMandateDocument:
                %s  blueId: %s
                """.formatted(
                indent,
                indent,
                actor,
                indent,
                indent,
                initialMandateDocumentBlueId);
    }
}
