package com.hermes.android.linux;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HermesInstaller 纯逻辑单测: 配置生成 (.env/config.yaml) 与解压安全 (zip-slip)。
 * (安装流程要真机 proot/网络, 不在本地单测范围)
 */
public class HermesInstallerTest {

    // ==================== 配置生成 ====================

    @Test
    public void buildEnvContent_full() {
        String env = HermesInstaller.buildEnvContent("https://api.deepseek.com/v1", "sk-abc");
        assertTrue(env.contains("OPENAI_API_KEY=sk-abc"));
        assertTrue(env.contains("OPENAI_BASE_URL=https://api.deepseek.com/v1"));
    }

    @Test
    public void buildEnvContent_missingParts() {
        assertEquals("", HermesInstaller.buildEnvContent("", ""));
        String env = HermesInstaller.buildEnvContent(null, "sk-abc");
        assertTrue(env.contains("OPENAI_API_KEY=sk-abc"));
        assertFalse(env.contains("OPENAI_BASE_URL"));
    }

    @Test
    public void buildConfigYaml_namedCustomProvider() {
        /* 自定义 OpenAI 兼容端点 → providers 块注册, model.provider 指向别名 */
        String yaml = HermesInstaller.buildConfigYaml("mimo-v2.5-pro", "https://api.xiaomimimo.com/v1");
        assertTrue(yaml.contains("default: \"mimo-v2.5-pro\""));
        assertTrue(yaml.contains("provider: \"mov-custom\""));
        assertTrue(yaml.contains("mov-custom:"));
        assertTrue(yaml.contains("base_url: \"https://api.xiaomimimo.com/v1\""));
        assertTrue("key 走 key_env 引用, 不明文", yaml.contains("key_env: \"OPENAI_API_KEY\""));
        assertFalse(yaml.contains("api_key:"));
    }

    @Test
    public void buildConfigYaml_noBaseUrl_minimal() {
        String yaml = HermesInstaller.buildConfigYaml("deepseek-chat", "");
        assertTrue(yaml.contains("default: \"deepseek-chat\""));
        assertFalse(yaml.contains("providers:"));
    }

    @Test
    public void buildConfigYaml_escapesQuotes() {
        /* 模型名/端点含引号反斜杠时必须转义, 防 YAML 注入 */
        String yaml = HermesInstaller.buildConfigYaml("ev\"il\nmodel: x", "");
        assertTrue(yaml.contains("ev\\\"il"));
        assertFalse(yaml.contains("base_url"));
    }

    // ==================== 解压安全 ====================

    private static byte[] makeZip(String... entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes("UTF-8"));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    public void unzip_normalEntries() throws Exception {
        File dir = Files.createTempDirectory("zip").toFile();
        HermesInstaller.unzip(new ByteArrayInputStream(
                makeZip("pyproject.toml", "[project]", "a/b/c.py", "print(1)")), dir);
        assertTrue(new File(dir, "pyproject.toml").isFile());
        assertTrue(new File(dir, "a/b/c.py").isFile());
    }

    @Test
    public void unzip_zipSlipRejected() throws Exception {
        /* zip-slip: ../ 条目必须被丢弃, 不得写出目标目录外 */
        File dir = Files.createTempDirectory("zip").toFile();
        HermesInstaller.unzip(new ByteArrayInputStream(
                makeZip("../evil.txt", "pwned", "ok.txt", "fine")), dir);
        assertTrue(new File(dir, "ok.txt").isFile());
        assertFalse(new File(dir.getParentFile(), "evil.txt").exists());
    }
}
