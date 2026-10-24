package com.zifang.z.agent.kernel.types;

/**
 * 音频格式 —— 裸字节流必须带着格式, 否则消费方无法播放.
 *
 * <p>为什么进 kernel: 语音链路上有三处要传"一串字节 + 它是什么":
 * ASR 的输入音频、TTS 的输出音频、实时会话里的语音帧.
 * 以前这三处都只传 {@code byte[]}, 消费方只能靠约定猜采样率 ⇒ 格式不一致时表现为"声音变调/变慢",
 * 而且无从排查. 有了这个类型, 格式跟着字节走.
 *
 * <p>纯 POJO, 不依赖任何运行时.
 *
 * @author yuku123
 */
public final class AudioFormat {

    /** 裸 PCM(16bit 线性). */
    public static final String PCM = "pcm";
    /** MP3 压缩流. */
    public static final String MP3 = "mp3";
    /** FLAC 无损压缩流. */
    public static final String FLAC = "flac";
    /** WAV 容器(带文件头). */
    public static final String WAV = "wav";

    private final String encoding;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;

    public AudioFormat(String encoding, int sampleRate, int channels, int bitsPerSample) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
    }

    /** 16bit 线性 PCM —— 语音链路的默认格式(可直接喂给浏览器 AudioWorklet). */
    public static AudioFormat pcm16(int sampleRate, int channels) {
        return new AudioFormat(PCM, sampleRate, channels, 16);
    }

    public String getEncoding() {
        return encoding;
    }

    /** 采样率 Hz. */
    public int getSampleRate() {
        return sampleRate;
    }

    /** 声道数. */
    public int getChannels() {
        return channels;
    }

    /** 位深(仅 pcm 有意义). */
    public int getBitsPerSample() {
        return bitsPerSample;
    }

    public boolean isPcm() {
        return PCM.equalsIgnoreCase(encoding);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AudioFormat)) {
            return false;
        }
        AudioFormat that = (AudioFormat) o;
        return sampleRate == that.sampleRate
                && channels == that.channels
                && bitsPerSample == that.bitsPerSample
                && (encoding == null ? that.encoding == null : encoding.equals(that.encoding));
    }

    @Override
    public int hashCode() {
        int result = encoding == null ? 0 : encoding.hashCode();
        result = 31 * result + sampleRate;
        result = 31 * result + channels;
        result = 31 * result + bitsPerSample;
        return result;
    }

    @Override
    public String toString() {
        return "AudioFormat{" + encoding + "," + sampleRate + "Hz," + channels + "ch,"
                + bitsPerSample + "bit}";
    }
}
