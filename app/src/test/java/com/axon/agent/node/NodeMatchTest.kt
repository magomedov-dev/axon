package com.axon.agent.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeMatchTest {

    @Test
    fun exact() {
        assertTrue(NodeMatch.matches("Войти", "Войти", NodeMatch.EXACT))
        assertFalse(NodeMatch.matches("Войти", "Вой", NodeMatch.EXACT))
    }

    @Test
    fun contains() {
        assertTrue(NodeMatch.matches("Sign in to continue", "Sign in", NodeMatch.CONTAINS))
        assertFalse(NodeMatch.matches("Sign in", "Register", NodeMatch.CONTAINS))
    }

    @Test
    fun regex_findsAnywhere() {
        assertTrue(NodeMatch.matches("Error: code 42", """code \d+""", NodeMatch.REGEX))
        assertTrue(NodeMatch.matches("Axon", "^Ax", NodeMatch.REGEX))
        assertFalse(NodeMatch.matches("Axon", """^\d+$""", NodeMatch.REGEX))
    }

    @Test
    fun nullActual_neverMatches() {
        assertFalse(NodeMatch.matches(null, "anything", NodeMatch.EXACT))
        assertFalse(NodeMatch.matches(null, "x", NodeMatch.CONTAINS))
        assertFalse(NodeMatch.matches(null, ".*", NodeMatch.REGEX))
    }
}
