/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceAssistantLaunchRequests")
class VoiceAssistantLaunchRequestsTest {
    @Test
    fun `requestOpen is buffered until popup host collects it`() =
        runTest {
            val requests = VoiceAssistantLaunchRequests()

            requests.requestOpen()

            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    requests.requests.first()
                }.await()
            assertNotNull(received)
        }
}
