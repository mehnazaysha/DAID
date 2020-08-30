package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

@JsType
public interface AsyncReader extends AutoCloseable {

    CompletableFuture<AsyncReader> seekJS(int high32, int low32);

    @JsIgnore
    default CompletableFuture<AsyncReader> seek(long offset) {
        return seekJS((int)(offset >> 32), (int)offset);
    }

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length);

    /**Files
     *  reset to original starting position
     * @return
     */
    CompletableFuture<AsyncReader> reset();

    /**
     * Close and dispose of any resources
     */
    void close();

    @JsIgnore
    default <T> CompletableFuture<Long> parseStream(Function<Cborable, T> fromCbor, Consumer<T> accumulator, long maxBytesToRead) {
        return parseStreamRecurse(new byte[0], fromCbor, accumulator, maxBytesToRead);
    }

    /** Convert reader into a stream of CborObjects
     *
     * @param prefix any bytes from a partial object read that will form the prefix of this read
     * @param fromCbor The cbor converter
     * @param accumulator The results consumer
     * @param maxBytesToRead There must be at least this many bytes left in this stream or an EOF will result
     * @param <T>
     * @return
     */
    @JsIgnore
    default <T> CompletableFuture<Long> parseStreamRecurse(byte[] prefix, Function<Cborable, T> fromCbor, Consumer<T> accumulator, long maxBytesToRead) {
        if (maxBytesToRead == 0)
            return CompletableFuture.completedFuture(0L);
        byte[] buf = new byte[Chunk.MAX_SIZE];
        System.arraycopy(prefix, 0, buf, 0, prefix.length);
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        return readIntoArray(buf, prefix.length, (int) Math.min((long)(buf.length - prefix.length), maxBytesToRead))
                .thenCompose(bytesRead -> {
                    for (int localOffset = 0; localOffset < bytesRead;) {
                        try {
                            CborObject readObject = CborObject.read(in, bytesRead);
                            accumulator.accept(fromCbor.apply(readObject));
                            localOffset += readObject.toByteArray().length;
                        } catch (RuntimeException e) {
                            int fromThisChunk = localOffset;
                            return parseStreamRecurse(Arrays.copyOfRange(buf, localOffset, bytesRead), fromCbor, accumulator,
                                    maxBytesToRead - bytesRead)
                                    .thenApply(rest -> rest + fromThisChunk);
                        }
                    }
                    return parseStream(fromCbor, accumulator, maxBytesToRead - bytesRead)
                            .thenApply(rest -> rest + bytesRead);
                });
    }

    @JsType
    class ArrayBacked implements AsyncReader {
        private final byte[] data;
        private int index = 0;

        public ArrayBacked(byte[] data) {
            this.data = data;
        }

        @Override
        public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
            if (high32 != 0)
                throw new IllegalArgumentException("Cannot have arrays larger than 4GiB!");
            index += low32;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            System.arraycopy(data, index, res, offset, length);
            index += length;
            return CompletableFuture.completedFuture(length);
        }

        @Override
        public CompletableFuture<AsyncReader> reset() {
            index = 0;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void close() {
        }
    }

    static AsyncReader build(byte[] data) {
        return new ArrayBacked(data);
    }
}
