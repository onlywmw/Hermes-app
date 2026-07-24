package com.hermes.android.agent;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ProductDigest 纯函数单测 (M-QUALITY): 交互元素提取 / 死按钮识别 / 自检清单解析。
 */
public class ProductDigestTest {

    // ==================== 交互元素提取 ====================

    @Test
    public void extract_findsDeadButton_emptyHandler() {
        /* 死按钮: onclick 指向空函数 → 清单必须标出【空函数!】 */
        String html = "<html><body>"
                + "<button id=\"scan\" onclick=\"doScan()\">扫一扫</button>"
                + "<script>function doScan(){}</script>"
                + "</body></html>";
        String out = ProductDigest.extractInteractiveElements(html);
        assertTrue("列出按钮", out.contains("扫一扫"));
        assertTrue("指出绑定", out.contains("doScan()"));
        assertTrue("必须标空函数: " + out, out.contains("【空函数!】"));
        assertTrue("空函数体名单", out.contains("doScan"));
    }

    @Test
    public void extract_flagsButtonWithoutOnclick() {
        String out = ProductDigest.extractInteractiveElements(
                "<button>点我</button>");
        assertTrue(out.contains("【无 onclick】"));
    }

    @Test
    public void extract_realHandlerNotFlagged() {
        String html = "<button onclick=\"go()\">开始</button>"
                + "<script>function go(){ location.href='#game'; }</script>";
        String out = ProductDigest.extractInteractiveElements(html);
        assertTrue(out.contains("go()"));
        assertFalse("真实现不得误标: " + out, out.contains("【空函数!】"));
    }

    @Test
    public void extract_addEventListenerListed() {
        String out = ProductDigest.extractInteractiveElements(
                "<script>document.addEventListener('keydown', h); b.addEventListener(\"click\", g);</script>");
        assertTrue(out.contains("addEventListener"));
        assertTrue(out.contains("keydown"));
        assertTrue(out.contains("click"));
    }

    @Test
    public void extract_arrowEmptyFunction() {
        String html = "<button onclick=\"f()\">x</button><script>const f = () => {};</script>";
        String out = ProductDigest.extractInteractiveElements(html);
        assertTrue(out.contains("【空函数!】"));
    }

    @Test
    public void extract_emptyInput() {
        assertEquals("(无)", ProductDigest.extractInteractiveElements(""));
        assertEquals("(无)", ProductDigest.extractInteractiveElements(null));
    }

    // ==================== build 摘要 ====================

    @Test
    public void build_capsPerFileAndFlagsHtml() {
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append('x');
        files.put("big.html", big.toString());
        files.put("note.py", "print(1)");
        String out = ProductDigest.build(files);
        assertTrue(out.contains("=== big.html"));
        assertTrue("单文件截断标注", out.contains("截断"));
        assertTrue("HTML 文件附交互清单", out.contains("[交互元素清单]"));
        assertTrue(out.contains("=== note.py"));
    }

    @Test
    public void build_emptyMap() {
        assertEquals("(无产物内容)", ProductDigest.build(new LinkedHashMap<String, String>()));
    }

    // ==================== 自检清单解析 ====================

    @Test
    public void parseChecklist_realAndDemo() {
        List<ProductDigest.CheckItem> items = ProductDigest.parseChecklist(
                "做好了。\n- ✅ 界面: 真实现\n- ⚠️ 扫一扫: 演示版(无相机)");
        assertEquals(2, items.size());
        assertTrue(items.get(0).real);
        assertEquals("界面: 真实现", items.get(0).text);
        assertFalse(items.get(1).real);
        assertEquals("扫一扫: 演示版(无相机)", items.get(1).text);
    }

    @Test
    public void parseChecklist_noMarkers() {
        assertTrue(ProductDigest.parseChecklist("普通交付说明").isEmpty());
        assertTrue(ProductDigest.parseChecklist(null).isEmpty());
    }

    // ==================== isTextFile ====================

    @Test
    public void isTextFile_filters() {
        assertTrue(ProductDigest.isTextFile("a.html"));
        assertTrue(ProductDigest.isTextFile("b.py"));
        assertTrue(ProductDigest.isTextFile("c.md"));
        assertFalse(ProductDigest.isTextFile("d.apk"));
        assertFalse(ProductDigest.isTextFile("e.png"));
        assertFalse(ProductDigest.isTextFile(null));
    }
}
