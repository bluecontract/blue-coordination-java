package blue.coordination.examples.support;

import java.util.Objects;

/** Every identity-affecting constant in a prepared Timeline entry shape. */
record MyOsEntryTemplateKey(
        String canonicalEnvironmentIdentity,
        String timelineId,
        String actorYaml,
        String operation,
        String sourceChannel,
        String handlerChannel,
        String requestYaml,
        String authorityYaml,
        boolean hasPreviousEntry) {

    MyOsEntryTemplateKey {
        canonicalEnvironmentIdentity = text(
                canonicalEnvironmentIdentity,
                "canonicalEnvironmentIdentity");
        timelineId = text(timelineId, "timelineId");
        actorYaml = text(actorYaml, "actorYaml");
        operation = text(operation, "operation");
        sourceChannel = text(sourceChannel, "sourceChannel");
        handlerChannel = text(handlerChannel, "handlerChannel");
        requestYaml = text(requestYaml, "requestYaml");
        authorityYaml = Objects.requireNonNull(
                authorityYaml, "authorityYaml");
    }

    static MyOsEntryTemplateKey of(
            String environmentIdentity,
            String timelineId,
            MyOsDemoActor actor,
            MyOsDemoOperation operation,
            boolean hasPreviousEntry) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operation, "operation");
        return new MyOsEntryTemplateKey(
                environmentIdentity,
                timelineId,
                actor.toYaml(0),
                operation.operation(),
                operation.sourceChannel(),
                operation.handlerChannel(),
                operation.requestYaml(),
                operation.authority() == null
                        ? ""
                        : operation.authority().toYaml(0),
                hasPreviousEntry);
    }

    private static String text(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        return checked;
    }
}
