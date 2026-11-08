package io.github.taehunkim.malbotdari.chat

import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactChatToolTest {
    @Test fun compactionPreservesRequiredFieldsEnumsAndExecution() {
        val tool = object : ChatTool {
            override val name = "read_file"
            override val description = "Useful tool. ".repeat(100)
            override val parameters = """{"type":"object","description":"verbose","properties":{"description":{"type":"string"},"item_id":{"type":"integer","description":"verbose"},"mode":{"type":"string","enum":["text","image"]}},"required":["item_id"]}"""
            override fun execute(args: JSONObject) = args.getLong("item_id").toString()
        }
        val compact = CompactChatTool(tool)
        val schema = JSONObject(compact.parameters)
        assertFalse(schema.has("description"))
        assertTrue(schema.getJSONObject("properties").has("description"))
        assertFalse(schema.getJSONObject("properties").getJSONObject("item_id").has("description"))
        assertEquals("item_id", schema.getJSONArray("required").getString(0))
        assertEquals("image", schema.getJSONObject("properties").getJSONObject("mode").getJSONArray("enum").getString(1))
        assertEquals("42", compact.execute(JSONObject().put("item_id", 42)))
        assertTrue(compact.description.length <= 180)
    }
}
