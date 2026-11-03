package com.nineyin.tools.mpq;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 随机读抽象：MPQ 各处以绝对偏移读定长字节块，越界部分补零
 * （与 Rust 端 read_exact_or_eof 的零填充语义一致）。
 */
interface ByteReader {

    /** 文件总长。 */
    long length();

    /** 从 offset 起读 len 字节；越过 EOF 的部分以零填充。 */
    byte[] readAtOrEof(long offset, int len);

    /** 内存实现。 */
    final class Memory implements ByteReader {
        private final byte[] data;

        Memory(byte[] data) {
            this.data = data;
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public byte[] readAtOrEof(long offset, int len) {
            byte[] out = new byte[len];
            int available = (int) Math.max(0, Math.min(len, data.length - offset));
            if (available > 0) {
                System.arraycopy(data, (int) offset, out, 0, available);
            }
            return out;
        }
    }

    /** 文件实现（线程安全：以通道为锁串行化定位读）。 */
    final class File implements ByteReader {
        private final FileChannel channel;

        File(Path path) {
            try {
                this.channel = FileChannel.open(path, StandardOpenOption.READ);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public long length() {
            try {
                return channel.size();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public byte[] readAtOrEof(long offset, int len) {
            byte[] out = new byte[len];
            ByteBuffer buf = ByteBuffer.wrap(out);
            try {
                synchronized (channel) {
                    int filled = 0;
                    while (filled < len) {
                        int n = channel.read(buf, offset + filled);
                        if (n <= 0) {
                            break; // EOF：剩余保持为零
                        }
                        filled += n;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out;
        }

        void close() {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
