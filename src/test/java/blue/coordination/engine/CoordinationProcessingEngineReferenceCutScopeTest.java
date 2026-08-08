package blue.coordination.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for sparse planning across implicit contracts metadata. */
final class CoordinationProcessingEngineReferenceCutScopeTest {

    @Test
    void expandsRequiredContractsContainerButNotItsImplicitDescendants() {
        Set<String> processSurface = new LinkedHashSet<String>();
        CoordinationProcessingEngine.addProcessScopeSurface(
                processSurface, "/product");

        assertEquals(new LinkedHashSet<String>(Arrays.asList(
                        "/product", "/product/contracts")),
                processSurface);
        assertFalse(processSurface.contains(
                "/product/contracts/embedded/paths"));
        assertFalse(CoordinationProcessingEngine.isContractsDescendantPath(
                "/productConditions/hotel/product/contracts"));
        assertTrue(CoordinationProcessingEngine.isContractsDescendantPath(
                "/product/contracts/embedded/paths"));
        assertTrue(CoordinationProcessingEngine.isContractsDescendantPath(
                "/contracts/embedded/paths/0"));
    }

    @Test
    void reusesOnlyAnExactCanonicalRoleSurface() {
        assertTrue(CoordinationProcessingEngine
                .referenceCutRoleSurfacesMatch(
                        Arrays.asList("/", "/product/contracts", "/product"),
                        Arrays.asList("/product", "/", "/product/contracts")),
                "canonical path order must not prevent an exact reuse");
        assertFalse(CoordinationProcessingEngine
                .referenceCutRoleSurfacesMatch(
                        Arrays.asList(
                                "/",
                                "/product",
                                "/product/contracts",
                                "/product/handler"),
                        Arrays.asList(
                                "/",
                                "/product",
                                "/product/contracts")),
                "a PROCESS-narrowed surface is not a handoff fallback");
    }
}
