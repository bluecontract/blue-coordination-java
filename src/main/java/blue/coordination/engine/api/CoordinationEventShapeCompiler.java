package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Compiles one operation shape using the authoritative full splitter once. */
public final class CoordinationEventShapeCompiler {
    private final CoordinationEventAdmissionCompiler authoritativeCompiler;
    private final CoordinationEventShapeMetrics metrics;

    public CoordinationEventShapeCompiler(
            CoordinationEventAdmissionCompiler authoritativeCompiler,
            CoordinationEventShapeMetrics metrics) {
        this.authoritativeCompiler = Objects.requireNonNull(
                authoritativeCompiler, "authoritativeCompiler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Compiles immutable topology only. No future exact timestamp/prevEntry
     * instance is created, cached, admitted, or published by this method.
     */
    public CoordinationEventShapeTemplate compile(
            String shapeIdentity,
            Node resolvedPrototype,
            Collection<String> volatileLeafPointers) {
        String checkedShape = requireText(shapeIdentity, "shapeIdentity");
        Node checkedPrototype = Objects.requireNonNull(
                resolvedPrototype, "resolvedPrototype");
        Set<String> volatilePaths = new LinkedHashSet<String>();
        for (String pointer : Objects.requireNonNull(
                volatileLeafPointers, "volatileLeafPointers")) {
            volatilePaths.add(JsonPointer.canonicalize(
                    Objects.requireNonNull(pointer, "volatileLeafPointer")));
        }
        if (volatilePaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one volatile event leaf is required");
        }
        CoordinationVerifiedEventAdmission prototype =
                authoritativeCompiler.compile(checkedPrototype);
        CoordinationEventShapeTemplate result =
                CoordinationEventShapeTemplate.fromAuthoritativePrototype(
                        checkedShape,
                        prototype,
                        volatilePaths,
                        metrics);
        metrics.templateCompiled();
        return result;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
