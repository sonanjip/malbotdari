package io.github.taehunkim.malbotdari.chat

import io.github.taehunkim.malbotdari.ml.llm.ChatTurn
import io.github.taehunkim.malbotdari.ml.llm.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextTest {
    private fun turn(role: TurnRole, chars: Int) = ChatTurn(role, "x".repeat(chars))

    @Test fun keepsTheNewestTurnsThatFit() {
        val history = listOf(turn(TurnRole.USER, 300), turn(TurnRole.ASSISTANT, 300), turn(TurnRole.USER, 30), turn(TurnRole.ASSISTANT, 30))
        val all = ContextBudget.trim(history, 10_000)
        assertEquals(4, all.turns.size); assertEquals(0, all.dropped)
        val tight = ContextBudget.trim(history, 60)
        assertEquals("only the last pair fits", 2, tight.turns.size); assertEquals(2, tight.dropped)
        assertEquals(TurnRole.USER, tight.turns.first().role)
    }

    @Test fun neverStartsWithAnAssistantTurn() {
        val history = listOf(turn(TurnRole.USER, 600), turn(TurnRole.ASSISTANT, 30), turn(TurnRole.USER, 30), turn(TurnRole.ASSISTANT, 30))
        // Budget fits the last three turns, but the oldest of those is the assistant's reply to a dropped question.
        val t = ContextBudget.trim(history, 70)
        assertEquals(TurnRole.USER, t.turns.first().role)
        assertEquals(2, t.turns.size)
    }

    @Test fun emptyBudgetKeepsNothing() {
        val t = ContextBudget.trim(listOf(turn(TurnRole.USER, 30), turn(TurnRole.ASSISTANT, 30)), 0)
        assertTrue(t.turns.isEmpty()); assertEquals(2, t.dropped)
}
}
