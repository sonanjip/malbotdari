package io.github.taehunkim.malbotdari.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateImageToolTest {
    @Test
    fun toolSpecHasCorrectNameAndParameters() {
        val tool = GenerateImageTool { _, _ -> }
        assertEquals(ChatToolNames.GENERATE_IMAGE, tool.name)
        val params = JSONObject(tool.parameters)
        assertEquals("object", params.getString("type"))
        assertTrue(params.getJSONObject("properties").has("prompt"))
        assertTrue(params.getJSONObject("properties").has("seed"))
    }

    @Test
    fun executeRegistersRequestAndReturnsGeneratingStatus() {
        var requestedPrompt = ""
        var requestedSeed = -1L
        val tool = GenerateImageTool { prompt, seed ->
            requestedPrompt = prompt
            requestedSeed = seed
        }

        val args = JSONObject().put("prompt", "a beautiful sunset").put("seed", 999L)
        val result = JSONObject(tool.execute(args))

        assertEquals("generating", result.getString("status"))
        assertEquals("a beautiful sunset", result.getString("prompt"))
        assertEquals("a beautiful sunset", requestedPrompt)
        assertEquals(999L, requestedSeed)
    }

    @Test
    fun emptyPromptReturnsError() {
        val tool = GenerateImageTool { _, _ -> }
        val result = tool.execute(JSONObject().put("prompt", "   "))
        assertTrue(result.startsWith("Error"))
    }
}
