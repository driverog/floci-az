package io.floci.az.services.acr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository namespacing the proxy applies: one shared container backs every registry, so the
 * registry name is prefixed on the way in and stripped from everything the client reads back.
 */
@DisplayName("AcrRegistryProxy: repository prefixing")
class AcrRegistryProxyTest {

    @Test
    void prefixesRepositoryScopedPathsWithTheRegistryName() {
        assertEquals("v2/myreg/app/manifests/v1",
                AcrRegistryProxy.backendPath("myreg", "v2/app/manifests/v1"));
        assertEquals("v2/myreg/team/app/blobs/sha256:abc",
                AcrRegistryProxy.backendPath("myreg", "v2/team/app/blobs/sha256:abc"));
        assertEquals("v2/myreg/app/blobs/uploads/",
                AcrRegistryProxy.backendPath("myreg", "v2/app/blobs/uploads/"));
        assertEquals("v2/myreg/app/tags/list",
                AcrRegistryProxy.backendPath("myreg", "v2/app/tags/list"));
    }

    @Test
    void leavesRegistryScopedPathsAlone() {
        assertEquals("v2/", AcrRegistryProxy.backendPath("myreg", "v2/"));
        assertEquals("v2/_catalog", AcrRegistryProxy.backendPath("myreg", "v2/_catalog"));
    }

    @Test
    void stripsThePrefixFromAnUploadSessionLocation() {
        assertEquals("/v2/app/blobs/uploads/abc-123?_state=xyz",
                AcrRegistryProxy.clientLocation("myreg", "/v2/myreg/app/blobs/uploads/abc-123?_state=xyz"));
    }

    @Test
    void rewritesAnAbsoluteLocationToAPathTheClientCanFollow() {
        assertEquals("/v2/app/blobs/uploads/abc-123",
                AcrRegistryProxy.clientLocation("myreg",
                        "http://floci-az-acr-registry:5000/v2/myreg/app/blobs/uploads/abc-123"));
    }

    @Test
    void leavesALocationThatCarriesNoPrefixUntouched() {
        assertEquals("/v2/other/manifests/v1",
                AcrRegistryProxy.clientLocation("myreg", "/v2/other/manifests/v1"));
        assertEquals(null, AcrRegistryProxy.clientLocation("myreg", null));
    }

    @Test
    void catalogShowsOnlyThisRegistrysRepositoriesWithoutThePrefix() {
        byte[] shared = ("{\"repositories\":[\"myreg/app\",\"myreg/team/api\",\"otherreg/app\"]}")
                .getBytes(StandardCharsets.UTF_8);

        String filtered = new String(AcrRegistryProxy.filterCatalog("myreg", shared), StandardCharsets.UTF_8);

        assertEquals("{\"repositories\":[\"app\",\"team/api\"]}", filtered);
    }

    @Test
    void tagsListNamesTheRepositoryTheClientAskedAbout() {
        byte[] backend = "{\"name\":\"myreg/team/api\",\"tags\":[\"v1\"]}".getBytes(StandardCharsets.UTF_8);

        assertEquals("{\"name\":\"team/api\",\"tags\":[\"v1\"]}",
                new String(AcrRegistryProxy.rewriteBody("myreg", "v2/team/api/tags/list", backend),
                        StandardCharsets.UTF_8));
    }

    @Test
    void onlyBodiesThatNameRepositoriesAreRewritten() {
        assertTrue(AcrRegistryProxy.rewritesBody("v2/_catalog"));
        assertTrue(AcrRegistryProxy.rewritesBody("v2/app/tags/list"));
        assertFalse(AcrRegistryProxy.rewritesBody("v2/app/manifests/v1"));
        assertFalse(AcrRegistryProxy.rewritesBody("v2/app/blobs/sha256:abc"));
    }

    @Test
    void catalogPassesThroughUnchangedWhenTheBodyIsNotTheExpectedShape() {
        byte[] notJson = "<html/>".getBytes(StandardCharsets.UTF_8);

        assertEquals("<html/>",
                new String(AcrRegistryProxy.filterCatalog("myreg", notJson), StandardCharsets.UTF_8));
    }
}
