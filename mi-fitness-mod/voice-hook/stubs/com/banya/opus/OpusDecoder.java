package com.banya.opus;
public class OpusDecoder {
    public OpusDecoder(int rate, int channels, int pcmFrameBytes, int opusFrameBytes) {}
    public int a() { return 0; }                         // createDecoder -> handle
    public byte[] b(int h, byte[] data, int len) { return null; } // decode N*80 bytes -> N*640
    public void c(int h) {}                              // destroyDecoder
}
