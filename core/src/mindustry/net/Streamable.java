package mindustry.net;

import mindustry.net.Packets.*;

import java.io.*;
import java.nio.channels.*;
import java.util.*;

public class Streamable extends Packet{
    public static final int maxStreamBytes = 256 * 1024 * 1024;
    public static final int maxTextureStreamBytes = NetworkIO.maxAssetBytes + 65535 + 6;
    public static final int maxRequiredAssetStreamBytes = 2 * 1024 * 1024;

    public transient ByteArrayInputStream stream;

    /** Also usable by StreamBegin.read before dispatch. Invalid framing must not allocate a builder. */
    public static Streamable validateStreamBegin(StreamBegin begin){
        Packet packet;
        try{
            packet = Net.newPacket(begin.type);
        }catch(IndexOutOfBoundsException e){
            throw new IllegalArgumentException("Invalid stream packet type: " + (begin.type & 0xff), e);
        }
        if(!(packet instanceof Streamable streamable)) throw new IllegalArgumentException("Packet is not streamable: " + (begin.type & 0xff));
        int maximum = streamable instanceof TextureStream ? maxTextureStreamBytes :
            streamable instanceof AssetRequirementStream ? maxRequiredAssetStreamBytes : maxStreamBytes;
        if(begin.total < 0 || begin.total > maximum) throw new IllegalArgumentException("Invalid stream total: " + begin.total);
        return streamable;
    }

    @Override
    public int getPriority(){
        return priorityHigh;
    }

    @Override
    public boolean allow(boolean server){
        return !server;
    }

    /** If true, this stream is handled as soon as it is received, instead of all at once. */
    public boolean incremental(){
        return false;
    }

    public static class StreamBuilder{
        public final int id;
        public final byte type;
        public final int total;
        public ByteArrayOutputStream stream = new ByteArrayOutputStream();
        public final boolean incremental;
        public final IncrementalStream incrementalStream;
        public int received;
        private boolean closed;

        public StreamBuilder(StreamBegin begin, boolean incremental){
            Streamable packet = validateStreamBegin(begin);
            if(incremental != packet.incremental()) throw new IllegalArgumentException("Incorrect incremental stream mode");
            id = begin.id;
            type = begin.type;
            total = begin.total;
            this.incremental = incremental;
            incrementalStream = incremental ? new IncrementalStream(total) : null;
        }

        public float progress(){
            return total == 0 ? 1f : (float)received / total;
        }

        public void add(byte[] bytes){
            if(closed) throw new IllegalStateException("Stream builder is closed");
            if(bytes == null || bytes.length > total - received) throw new IllegalArgumentException("Stream chunk exceeds advertised total");
            if(bytes.length == 0) return;
            if(incrementalStream != null){
                incrementalStream.add(bytes);
            }else{
                stream.write(bytes, 0, bytes.length);
            }
            received += bytes.length;
        }

        public Streamable build(){
            if(closed || !isDone() || incremental) throw new IllegalStateException("Cannot build an incomplete, closed or incremental stream");
            Streamable s = Net.newPacket(type);
            s.stream = new ByteArrayInputStream(stream.toByteArray());
            return s;
        }

        public boolean isDone(){
            return received == total;
        }

        public void onClose(Runnable listener){
            if(incrementalStream != null) incrementalStream.closeListener = listener;
        }

        public void close(){
            closed = true;
            stream = new ByteArrayOutputStream();
            if(incrementalStream != null) incrementalStream.close();
        }
    }

    /** Bounded, coalescing buffer: producers never wait for a consumer or allocate a queue node per packet. */
    static class IncrementalStream extends InputStream{
        private static final int pageBytes = 8192;
        private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
        private final int total;
        private int written;
        private boolean isClosed, isFinished;
        private Runnable closeListener;
        private final byte[] singleByte = new byte[1];

        IncrementalStream(int total){
            this.total = total;
        }

        synchronized void add(byte[] bytes){
            if(isClosed || isFinished) throw new IllegalStateException("Incremental stream is closed");
            if(bytes == null || bytes.length > total - written) throw new IllegalArgumentException("Incremental stream exceeds byte budget");
            int offset = 0;
            while(offset < bytes.length){
                Buffer tail = queue.peekLast();
                if(tail == null || tail.end == tail.bytes.length){
                    tail = new Buffer(Math.min(pageBytes, total - written));
                    queue.addLast(tail);
                }
                int amount = Math.min(bytes.length - offset, tail.bytes.length - tail.end);
                System.arraycopy(bytes, offset, tail.bytes, tail.end, amount);
                tail.end += amount;
                written += amount;
                offset += amount;
            }
            notifyAll();
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException{
            if(offset < 0 || length < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException();
            if(length == 0) return 0;
            while(queue.isEmpty() && !isClosed && !isFinished){
                try{
                    wait();
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if(isClosed) throw new ClosedChannelException();
            Buffer first = queue.peekFirst();
            if(first == null) return -1;
            int amount = Math.min(length, first.end - first.offset);
            System.arraycopy(first.bytes, first.offset, bytes, offset, amount);
            first.offset += amount;
            if(first.offset == first.end) queue.removeFirst();
            return amount;
        }

        @Override
        public int read() throws IOException{
            int result = read(singleByte, 0, 1);
            return result == -1 ? -1 : singleByte[0] & 0xff;
        }

        /** Allows buffered data to drain normally, then reports EOF. */
        public synchronized void finish(){
            if(isClosed) return;
            if(written != total) throw new IllegalStateException("Cannot finish an incomplete stream");
            isFinished = true;
            notifyAll();
        }

        /** Cancels reads immediately, including readers waiting for more data. */
        @Override
        public synchronized void close(){
            if(isClosed) return;
            isClosed = true;
            queue.clear();
            notifyAll();
            if(closeListener != null) closeListener.run();
        }

        private static class Buffer{
            final byte[] bytes;
            int offset, end;

            Buffer(int length){
                bytes = new byte[length];
            }
        }
    }
}
