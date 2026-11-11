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

    @Test
    fun parseToolCallTextSupportsAllTagVariants() {
        val t1 = ChatController.parseToolCallText("Here is the image:\n<tool name=\"generate_image\">A golden retriever in autumn leaves</tool>")
        assertEquals(ChatToolNames.GENERATE_IMAGE, t1?.name)
        assertEquals("A golden retriever in autumn leaves", t1?.argsJson?.optString("prompt"))

        val t2 = ChatController.parseToolCallText("<tool_call><function=generate_image><parameter=prompt>A neon cyberpunk alley</parameter></function></tool_call>")
        assertEquals(ChatToolNames.GENERATE_IMAGE, t2?.name)
        assertEquals("A neon cyberpunk alley", t2?.argsJson?.optString("prompt"))

        val t3 = ChatController.parseToolCallText("<function=generate_image>A serene mountain lake at dawn</function>")
        assertEquals(ChatToolNames.GENERATE_IMAGE, t3?.name)
        assertEquals("A serene mountain lake at dawn", t3?.argsJson?.optString("prompt"))

        val t4 = ChatController.parseToolCallText("<generate_image>A watercolor painting of a lighthouse</generate_image>")
        assertEquals(ChatToolNames.GENERATE_IMAGE, t4?.name)
        assertEquals("A watercolor painting of a lighthouse", t4?.argsJson?.optString("prompt"))

        val t5 = ChatController.parseToolCallText("<tool_call>A cozy library with warm lighting</tool_call>")
        assertEquals(ChatToolNames.GENERATE_IMAGE, t5?.name)
        assertEquals("A cozy library with warm lighting", t5?.argsJson?.optString("prompt"))
    }

    @Test
    fun stripToolCallTagsRemovesCompleteAndStreamingPartialTags() {
        assertEquals(
            "이미지를 생성했습니다.",
            ChatController.stripToolCallTags("이미지를 생성했습니다.\n<tool name=\"generate_image\">A cute cat sitting by the window</tool>"),
        )
        assertEquals(
            "이미지를 생성했습니다.",
            ChatController.stripToolCallTags("이미지를 생성했습니다.\n<tool_call>A cute cat sitting by the window</tool_call>"),
        )
        assertEquals(
            "이미지를 생성했습니다.",
            ChatController.stripToolCallTags("이미지를 생성했습니다.\n<generate_image>A cute cat sitting by the window</generate_image>"),
        )
        assertEquals(
            "잠시만 기다려 주세요.",
            ChatController.stripToolCallTags("잠시만 기다려 주세요.\n<tool name=\"generate_image\">A cute cat"),
        )
        assertEquals(
            "",
            ChatController.stripToolCallTags("<tool_call><function=generate_image><parameter=prompt>partial prompt"),
        )
        assertEquals(
            "이미지를 확인했습니다.",
            ChatController.stripToolCallTags("<ctrl42>call:view_image{item_id:2}<ctrl43>이미지를 확인했습니다."),
        )
        assertEquals(
            "",
            ChatController.stripToolCallTags("<ctrl42>call:view_image{item_id:2"),
        )
    }
}
