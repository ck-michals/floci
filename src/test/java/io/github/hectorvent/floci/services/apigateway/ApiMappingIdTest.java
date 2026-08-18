package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The v2 mapping id has to name exactly one stored record.
 *
 * <p>Writes canonicalise the root now, so the store cannot gain a record under "/" or "" through
 * any endpoint. State written before that change can still hold one, which is why the id is derived
 * from the path a record is stored under rather than from its canonical form: folding them together
 * would give two records one id, and a read or a delete would then pick between them.
 */
class ApiMappingIdTest {

    /** As the constructor stores it — it already folds null and "" to the canonical root. */
    private static BasePathMapping storedUnder(String basePath) {
        return new BasePathMapping(basePath, "api123", "$default");
    }

    /** As deserialization restores it, which is the only way a record holds a raw "" today. */
    private static BasePathMapping restoredUnder(String basePath) {
        BasePathMapping mapping = new BasePathMapping();
        mapping.setBasePath(basePath);
        return mapping;
    }

    @Test
    void rootLikePathsThatWerePersistedSeparatelyKeepSeparateIds() {
        String canonical = ApiGatewayController.apiMappingId(storedUnder("(none)"));
        String slash = ApiGatewayController.apiMappingId(storedUnder("/"));
        String empty = ApiGatewayController.apiMappingId(restoredUnder(""));

        assertNotEquals(canonical, slash);
        assertNotEquals(canonical, empty);
        assertNotEquals(slash, empty);
    }

    @Test
    void everyIdIsNonEmptyAndStable() {
        // A record restored with an empty path must still produce an id a caller can put in a URL.
        assertFalse(ApiGatewayController.apiMappingId(restoredUnder("")).isEmpty());
        assertFalse(ApiGatewayController.apiMappingId(restoredUnder(null)).isEmpty());

        assertEquals(ApiGatewayController.apiMappingId(storedUnder("orders")),
                ApiGatewayController.apiMappingId(storedUnder("orders")));
    }

    @Test
    void distinctPathsSharingAJavaHashKeepDistinctIds() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(ApiGatewayController.apiMappingId(storedUnder("Aa")),
                ApiGatewayController.apiMappingId(storedUnder("BB")));
    }
}
