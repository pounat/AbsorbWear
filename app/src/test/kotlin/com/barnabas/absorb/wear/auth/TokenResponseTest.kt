package com.barnabas.absorb.wear.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TokenResponseTest {
    @Test
    fun parsesNestedRefreshPair() {
        val tokens = parseWearRefreshTokens(
            """{"user":{"accessToken":"access","refreshToken":"refresh"}}""",
        )

        assertEquals("access", tokens?.accessToken)
        assertEquals("refresh", tokens?.refreshToken)
    }

    @Test
    fun rejectsHalfARefreshPair() {
        assertNull(parseWearRefreshTokens("""{"accessToken":"access"}"""))
    }

    @Test
    fun bookOrbitMayKeepItsRefreshToken() {
        val tokens = parseWearRefreshTokens(
            """{"accessToken":"access"}""",
            allowMissingRefreshToken = true,
        )

        assertEquals("access", tokens?.accessToken)
        assertNull(tokens?.refreshToken)
    }

    @Test
    fun oldPhonePayloadCannotRotateSharedSession() {
        val credentials = Json.decodeFromString<Credentials>(
            """{"serverUrl":"https://example.com","accessToken":"access","username":"reader"}""",
        )

        assertEquals("phone", credentials.source)
        assertFalse(credentials.supportsTokenReturn)
        assertFalse(credentials.isBookOrbit)
    }

    @Test
    fun phonePayloadNamesItsBackend() {
        val credentials = Json.decodeFromString<Credentials>(
            """{"serverUrl":"https://example.com","accessToken":"access","username":"reader","backend":"bookorbit"}""",
        )

        assertTrue(credentials.isBookOrbit)
    }

    @Test
    fun readsExpiryFromJwt() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"1","exp":1700000000}""".toByteArray())

        assertEquals(1_700_000_000_000L, jwtExpiryMillis("header.$payload.sig"))
    }

    @Test
    fun opaqueTokenHasNoExpiry() {
        assertNull(jwtExpiryMillis("not-a-jwt"))
        assertNull(jwtExpiryMillis("a.b.c"))
    }
}
