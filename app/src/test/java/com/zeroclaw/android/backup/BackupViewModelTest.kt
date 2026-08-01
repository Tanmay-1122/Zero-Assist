package com.zeroclaw.android.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.json.JSONObject

class BackupViewModelTest {
    @Test
    fun `derivePreferredDriveName prefers Google display name`() {
        assertEquals(
            "Tanmay",
            derivePreferredDriveName(
                displayName = "Tanmay",
                email = "tanmay@example.com",
            ),
        )
    }

    @Test
    fun `derivePreferredDriveName falls back to title cased email local part`() {
        assertEquals(
            "Tanmay Kumar Dev",
            derivePreferredDriveName(
                displayName = null,
                email = "tanmay.kumar_dev@example.com",
            ),
        )
    }

    @Test
    fun `derivePreferredDriveName falls back to User when nothing usable exists`() {
        assertEquals(
            "User",
            derivePreferredDriveName(
                displayName = "",
                email = "@example.com",
            ),
        )
    }

    @Test
    fun `seedUserNameIntoIdentityJson adds nested identity user name`() {
        val seededJson =
            seedUserNameIntoIdentityJson(
                identityJson = "",
                userName = "Tanmay",
            )

        val identity = JSONObject(seededJson).getJSONObject("identity")
        assertEquals("Tanmay", identity.getString("user_name"))
    }

    @Test
    fun `seedUserNameIntoIdentityJson preserves existing identity fields`() {
        val seededJson =
            seedUserNameIntoIdentityJson(
                identityJson = """{"identity":{"names":{"first":"Zero"}}}""",
                userName = "Tanmay",
            )

        val identity = JSONObject(seededJson).getJSONObject("identity")
        assertEquals("Tanmay", identity.getString("user_name"))
        assertEquals("Zero", identity.getJSONObject("names").getString("first"))
    }

    @Test
    fun `seedUserNameIntoIdentityJson keeps existing user names untouched`() {
        val seededJson =
            seedUserNameIntoIdentityJson(
                identityJson = """{"identity":{"user_name":"Existing"}}""",
                userName = "Tanmay",
            )

        val identity = JSONObject(seededJson).getJSONObject("identity")
        assertEquals("Existing", identity.getString("user_name"))
    }

    @Test
    fun `identityHasUserName detects nested or top level names`() {
        assertTrue(identityHasUserName("""{"identity":{"user_name":"Tanmay"}}"""))
        assertTrue(identityHasUserName("""{"user_name":"Tanmay"}"""))
        assertFalse(identityHasUserName("""{"identity":{"user_name":""}}"""))
        assertFalse(identityHasUserName(""))
    }
}
