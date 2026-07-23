package com.hermes.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.hermes.android.bridge.BridgeValidator;

import org.junit.Test;

/**
 * BridgeValidator 单测 — 参数校验层契约 (含「发送到桌面」安全回归: TC-S10)
 */
public class BridgeValidatorTest {

    // ---------- sanitizeLabel (桌面快捷方式标签消毒) ----------

    @Test
    public void sanitizeLabel_null_returnsEmpty() {
        assertEquals("", BridgeValidator.sanitizeLabel(null, 20));
    }

    @Test
    public void sanitizeLabel_removesControlChars() {
        assertEquals("snake游戏", BridgeValidator.sanitizeLabel("snake\n游戏\t", 20));
        assertEquals("abc", BridgeValidator.sanitizeLabel("abc", 20));
    }

    @Test
    public void sanitizeLabel_trimsWhitespace() {
        assertEquals("snake.html", BridgeValidator.sanitizeLabel("  snake.html  ", 20));
    }

    @Test
    public void sanitizeLabel_truncatesOverLimit() {
        String long25 = "一二三四五六七八九十一二三四五六七八九十一二三四五"; // 25 字
        assertEquals(20, BridgeValidator.sanitizeLabel(long25, 20).length());
    }

    @Test
    public void sanitizeLabel_maxLenZeroOrNegative_noTruncate() {
        assertEquals("abcdef", BridgeValidator.sanitizeLabel("abcdef", 0));
    }

    // ---------- checkPath 路径遍历拒绝 (TC-S10: pinFileShortcut("desk","../../x","t") 被拒) ----------

    @Test
    public void checkPath_dotDot_rejected() {
        assertNotNull(BridgeValidator.checkPath("../../x"));
        assertNotNull(BridgeValidator.checkPath("a/../../etc/hosts"));
    }

    @Test
    public void checkPath_absolute_rejected() {
        assertNotNull(BridgeValidator.checkPath("/sdcard/x"));
    }

    @Test
    public void checkPath_normal_accepted() {
        assertNull(BridgeValidator.checkPath("snake.html"));
        assertNull(BridgeValidator.checkPath("sub/dir/game.html"));
    }

    // ---------- checkRoomId ----------

    @Test
    public void checkRoomId_traversalChars_rejected() {
        assertNotNull(BridgeValidator.checkRoomId("../r1"));
        assertNotNull(BridgeValidator.checkRoomId("r 1"));
        assertNotNull(BridgeValidator.checkRoomId(""));
        assertNotNull(BridgeValidator.checkRoomId(null));
    }

    @Test
    public void checkRoomId_normal_accepted() {
        assertNull(BridgeValidator.checkRoomId("desk"));
        assertNull(BridgeValidator.checkRoomId("r1784738681371"));
    }
}
