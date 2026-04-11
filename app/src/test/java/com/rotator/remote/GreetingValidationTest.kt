package com.rotator.remote

import org.junit.Assert.*
import org.junit.Test

class GreetingValidationTest {
    @Test
    fun `old style greeting with colon passes`() {
        assertTrue(RotatorClient.isValidGreeting("ROTATOR_SERVER:foobar"))
    }

    @Test
    fun `new style greeting with space passes`() {
        assertTrue(RotatorClient.isValidGreeting("ROTATOR_SERVER v1.0"))
    }

    @Test
    fun `completely different string fails`() {
        assertFalse(RotatorClient.isValidGreeting("HELLO_SERVER"))
    }

    @Test
    fun `null greeting fails gracefully`() {
        assertFalse(RotatorClient.isValidGreeting(null))
    }
}
