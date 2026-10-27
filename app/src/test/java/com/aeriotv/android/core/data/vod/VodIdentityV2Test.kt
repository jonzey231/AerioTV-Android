package com.aeriotv.android.core.data.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodIdentityV2Test {
    @Test fun dropsTheApiKeyHash() {
        assertEquals("v2|p1|http://h:9191|bob", VodCatalogStore.v2KeyOf("v1|p1|http://h:9191|bob|12345"))
    }

    @Test fun keepsAnEmptyUsername() {
        assertEquals("v2|p1|http://h|", VodCatalogStore.v2KeyOf("v1|p1|http://h||0"))
    }

    @Test fun twoKeysOfOneUserMapToOneIdentity() {
        assertEquals(
            VodCatalogStore.v2KeyOf("v1|p1|u|bob|1"),
            VodCatalogStore.v2KeyOf("v1|p1|u|bob|-99"),
        )
    }

    @Test fun ignoresNonV1Keys() {
        assertNull(VodCatalogStore.v2KeyOf("v2|p1|u|bob"))
        assertNull(VodCatalogStore.v2KeyOf("v1|short"))
    }
}
