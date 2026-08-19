package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.DocumentId;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact immutable Contracts 1.0 release manifest bundled with the SDK. */
public final class BundledContracts10Release {
    private static final String RESOURCE =
            "/blue/coordination/sdk/contracts-1.0-release.properties";
    private static final Pattern SHA_256 = Pattern.compile(
            "^sha256:[0-9a-f]{64}$");
    private static final Manifest MANIFEST = load();

    private BundledContracts10Release() {
    }

    /** Returns the verified bundled release identities. */
    public static Manifest manifest() {
        return MANIFEST;
    }

    /** Creates exact low-level configuration for the supplied public Roots. */
    public static Contracts10Configuration configuration(
            Set<DocumentId> publicRoots) {
        return new Contracts10Configuration(
                MANIFEST.blueLanguageSpecification(),
                MANIFEST.contractsSpecification(),
                Objects.requireNonNull(publicRoots, "publicRoots"));
    }

    private static Manifest load() {
        Properties properties = new Properties();
        try (InputStream input = BundledContracts10Release.class
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing bundled Contracts release manifest "
                                + RESOURCE);
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
        return new Manifest(
                identity(properties, "blueLanguageSpecification"),
                identity(properties, "contractsSpecification"),
                identity(properties, "contractsRelease"),
                identity(properties, "fixturePackage"),
                identity(properties, "gasManifest"),
                identity(properties, "cyclicFinalizer"),
                identity(properties, "cyclicProofVerifier"));
    }

    private static String identity(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalStateException(
                    "Bundled Contracts release has invalid " + key);
        }
        return value;
    }

    /** Exact identities bound by the locally bundled Contracts 1.0 release. */
    public record Manifest(
            String blueLanguageSpecification,
            String contractsSpecification,
            String contractsRelease,
            String fixturePackage,
            String gasManifest,
            String cyclicFinalizer,
            String cyclicProofVerifier) {
        /** Revalidates values even when constructed by reflective tooling. */
        public Manifest {
            blueLanguageSpecification = requireIdentity(
                    blueLanguageSpecification, "blueLanguageSpecification");
            contractsSpecification = requireIdentity(
                    contractsSpecification, "contractsSpecification");
            contractsRelease = requireIdentity(
                    contractsRelease, "contractsRelease");
            fixturePackage = requireIdentity(
                    fixturePackage, "fixturePackage");
            gasManifest = requireIdentity(gasManifest, "gasManifest");
            cyclicFinalizer = requireIdentity(
                    cyclicFinalizer, "cyclicFinalizer");
            cyclicProofVerifier = requireIdentity(
                    cyclicProofVerifier, "cyclicProofVerifier");
        }

        private static String requireIdentity(String value, String label) {
            String checked = Objects.requireNonNull(value, label);
            if (!SHA_256.matcher(checked).matches()) {
                throw new IllegalArgumentException(
                        label + " must be a lowercase sha256 identity");
            }
            return checked;
        }
    }
}
