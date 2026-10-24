package com.zifang.z.agent.kernel.types;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AudioBytes} 契约测试.
 *
 * <p>为什么值得单独测: WAV 头是**手写的字节布局**, 错一个字段的后果很隐蔽 ——
 * 大多数播放器会"宽容地"放行, 但厂商 ASR 服务端会拒收或者识别出一堆噪声.
 * 所以这里把头里的每一个字段都钉死, 而不是只断言"长度对得上".
 */
class AudioBytesTest {

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) (b[off + i] & 0xFF));
        }
        return sb.toString();
    }

    @Test
    void wav头字段逐个对() {
        byte[] pcm = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] wav = AudioBytes.pcm16ToWav(pcm, 16000, 1);

        assertEquals(44 + pcm.length, wav.length, "WAV 应该是 44 字节头 + 裸 PCM");
        assertEquals("RIFF", ascii(wav, 0, 4));
        assertEquals(36 + pcm.length, le32(wav, 4), "RIFF 长度 = 36 + data 段长");
        assertEquals("WAVE", ascii(wav, 8, 4));
        assertEquals("fmt ", ascii(wav, 12, 4));
        assertEquals(16, le32(wav, 16), "fmt 段长度固定 16(PCM 无扩展字段)");
        assertEquals(1, le16(wav, 20), "format 必须是 1(PCM)");
        assertEquals(1, le16(wav, 22), "声道数");
        assertEquals(16000, le32(wav, 24), "采样率");
        assertEquals(16000 * 2, le32(wav, 28), "byteRate = 采样率 × 每帧字节数");
        assertEquals(2, le16(wav, 32), "blockAlign = 每帧字节数");
        assertEquals(16, le16(wav, 34), "位深");
        assertEquals("data", ascii(wav, 36, 4));
        assertEquals(pcm.length, le32(wav, 40), "data 段长度");

        byte[] body = new byte[pcm.length];
        System.arraycopy(wav, 44, body, 0, pcm.length);
        assertArrayEquals(pcm, body, "裸 PCM 必须原样落在 data 段里");
    }

    @Test
    void 双声道的byteRate与blockAlign翻倍() {
        byte[] wav = AudioBytes.pcm16ToWav(new byte[100], 24000, 2);
        assertEquals(24000 * 2 * 2, le32(wav, 28), "双声道 byteRate 要乘声道数");
        assertEquals(4, le16(wav, 32), "双声道 blockAlign = 2 字节 × 2 声道");
        assertEquals(2, le16(wav, 22));
    }

    @Test
    void 按AudioFormat取采样率与声道() {
        byte[] pcm = new byte[]{9, 8, 7};
        byte[] wav = AudioBytes.pcm16ToWav(pcm, AudioFormat.pcm16(16000, 1));
        assertEquals(16000, le32(wav, 24));
        assertEquals(1, le16(wav, 22));

        byte[] stereo = AudioBytes.pcm16ToWav(pcm, AudioFormat.pcm16(48000, 2));
        assertEquals(48000, le32(stereo, 24));
        assertEquals(2, le16(stereo, 22));
    }

    @Test
    void 空输入返回空数组() {
        assertEquals(0, AudioBytes.pcm16ToWav(null, 16000, 1).length);
        assertEquals(0, AudioBytes.pcm16ToWav(new byte[0], 16000, 1).length);
    }

    @Test
    void dataUrl带正确前缀且能原样解回() {
        byte[] wav = new byte[]{0x52, 0x49, 0x46, 0x46};
        String url = AudioBytes.toDataUrl(wav, "audio/wav");
        assertTrue(url.startsWith("data:audio/wav;base64,"), "MiMo 要的就是这个形状：" + url);

        String b64 = url.substring("data:audio/wav;base64,".length());
        assertArrayEquals(wav, Base64.getDecoder().decode(b64), "base64 必须能原样解回字节");
    }

    @Test
    void dataUrl缺省MIME回落到wav() {
        assertTrue(AudioBytes.toDataUrl(new byte[]{1}, null).startsWith("data:audio/wav;base64,"));
        assertTrue(AudioBytes.toDataUrl(new byte[]{1}, "").startsWith("data:audio/wav;base64,"));
    }

    @Test
    void 纯base64不带前缀() {
        String b64 = AudioBytes.toBase64(new byte[]{1, 2, 3});
        assertEquals("AQID", b64);
        assertEquals(0, AudioBytes.toBase64(null).length());
    }

    @Test
    void utf8按UTF8编码() {
        byte[] b = AudioBytes.utf8("天气");
        assertEquals(6, b.length, "一个汉字 3 字节");
        assertEquals("天气", new String(b, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, AudioBytes.utf8(null).length);
    }
}
