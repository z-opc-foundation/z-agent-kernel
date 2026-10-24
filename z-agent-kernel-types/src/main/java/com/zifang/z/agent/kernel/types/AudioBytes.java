package com.zifang.z.agent.kernel.types;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 音频字节的厂商无关工具 —— 把「裸 PCM」与「各家 ASR 要的容器」之间的差异收在一处.
 *
 * <p>为什么需要它: 语音识别厂商**几乎都不接受裸 PCM**, 都要 WAV/MP3 容器
 * （MiniMax {@code speech_to_text} 要 multipart 的 WAV; 小米 MiMo 要
 * {@code data:audio/wav;base64,...}; 百炼流式要裸二进制帧）.
 * ⇒ 每接一个厂商就抄一遍 WAV 头, 迟早抄错（字节序 / 长度 / 块对齐）.
 *
 * <p>本类只做**字节搬运**，不含任何厂商知识 ⇒ 放在 {@code types}（全仓共享基础类型）里，
 * 任何实现方（消费仓 / demo / 服务端）都能直接用.
 */
public final class AudioBytes {

    private AudioBytes() {
    }

    /**
     * 裸 PCM16 → 16bit WAV 容器.
     *
     * @param pcm        裸 PCM 采样(小端 16bit 有符号)
     * @param sampleRate 采样率
     * @param channels   声道数(1 = 单声道)
     * @return WAV 字节
     */
    public static byte[] pcm16ToWav(byte[] pcm, int sampleRate, int channels) {
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        int channelsCount = channels <= 0 ? 1 : channels;
        int byteRate = sampleRate * 2 * channelsCount;
        int blockAlign = 2 * channelsCount;
        int dataSize = pcm.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        writeAscii(out, "RIFF");
        writeLe32(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);            // PCM
        writeLe16(out, channelsCount);
        writeLe32(out, sampleRate);
        writeLe32(out, byteRate);
        writeLe16(out, blockAlign);
        writeLe16(out, 16);           // bits per sample
        writeAscii(out, "data");
        writeLe32(out, dataSize);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    /** 裸 PCM16 → WAV（用 {@link AudioFormat} 里的采样率/声道/位深） */
    public static byte[] pcm16ToWav(byte[] pcm, AudioFormat format) {
        if (format == null) {
            return pcm16ToWav(pcm, 16000, 1);
        }
        return pcm16ToWav(pcm, format.getSampleRate(), format.getChannels());
    }

    /**
     * 音频 → {@code data:{MIME};base64,{...}} 的 data URL.
     *
     * <p>小米 MiMo 的 {@code messages[].content[].input_audio.data} 就要这个形态,
     * 且**前缀的 MIME_TYPE 必须与实际格式一致**（官方文档明确要求）.
     *
     * @param audio    音频字节
     * @param mimeType 例如 {@code audio/wav}
     */
    public static String toDataUrl(byte[] audio, String mimeType) {
        StringBuilder sb = new StringBuilder("data:");
        sb.append(mimeType == null || mimeType.isEmpty() ? "audio/wav" : mimeType);
        sb.append(";base64,");
        sb.append(Base64.getEncoder().encodeToString(audio == null ? new byte[0] : audio));
        return sb.toString();
    }

    /** 音频 → 纯 base64（不带 data URL 前缀） */
    public static String toBase64(byte[] audio) {
        return Base64.getEncoder().encodeToString(audio == null ? new byte[0] : audio);
    }

    /** UTF-8 文本 → 字节（少数厂商要 text/plain 字段时用） */
    public static byte[] utf8(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}
