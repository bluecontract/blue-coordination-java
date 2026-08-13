package blue.coordination.processor;

import blue.language.processor.ContractProcessor;
import blue.language.processor.model.MarkerContract;

import java.util.Objects;

/**
 * Registers one current generated Repository marker as understood metadata.
 *
 * <p>Marker contracts have no executable callback. They still require an
 * exact runtime registration so the Contracts capability boundary can
 * distinguish a supported current marker from an unknown must-understand
 * contract.</p>
 */
final class CurrentRepositoryMarkerProcessor<T extends MarkerContract>
        implements ContractProcessor<T> {

    private final Class<T> contractType;

    CurrentRepositoryMarkerProcessor(Class<T> contractType) {
        this.contractType = Objects.requireNonNull(
                contractType, "contractType");
    }

    @Override
    public Class<T> contractType() {
        return contractType;
    }
}
