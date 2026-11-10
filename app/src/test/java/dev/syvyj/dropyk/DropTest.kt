package dev.syvyj.dropyk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DropTest {
    @Test fun safeName() {
        assertEquals("x.mp4", Drop.safeName("../../x.mp4"))
        assertEquals("b.jpg", Drop.safeName("a\\b.jpg"))
        assertEquals("file", Drop.safeName(".."))
        assertEquals("file", Drop.safeName(""))
        assertEquals("a_b_.txt", Drop.safeName("a:b\n.txt"))
        // Long names keep their extension, which picks the folder and the button.
        val long = Drop.safeName("v".repeat(200) + ".mp4")
        assertEquals(120, long.length)
        assertEquals("video/mp4", Drop.mimeOf(long))
    }

    @Test fun textOrLink() {
        assertEquals(DropState.Link("https://youtu.be/x"), Drop.textState("https://youtu.be/x"))
        assertEquals(DropState.Text("wifi pass 1234"), Drop.textState("wifi pass 1234"))
        // A link with words around it is text: opening it would drop the words.
        assertEquals(DropState.Text("see https://x.com"), Drop.textState("see https://x.com"))
        assertEquals(DropState.Text("javascript:alert(1)"), Drop.textState("javascript:alert(1)"))
    }

    @Test fun headerLines() {
        with(Drop) {
            assertEquals("GET / HTTP/1.1", "GET / HTTP/1.1\r\nHost: x\r\n".byteInputStream().line(MAX_LINE))
            assertNull("".byteInputStream().line(MAX_LINE))
            assertThrows(IllegalStateException::class.java) { "a".repeat(MAX_LINE + 1).byteInputStream().line(MAX_LINE) }
        }
    }
}
