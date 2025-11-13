package io.netty.contrib.multipart.vintage;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.micronaut.fuzzing.util.ByteSplitter;
import io.netty.buffer.ByteBuf;
import io.netty.contrib.multipart.FormDecoderException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import org.junit.jupiter.api.Assertions;

import java.io.Closeable;
import java.util.List;

abstract class AbstractComparisonTest extends AbstractFuzzTest {
    private static boolean logStackTraces = true;

    @MultipartFuzzTest
    @FuzzTest(maxDuration = "2h")
    public void compare(byte[] bytes) {
        try (Runner runner = new Runner()) {
            ByteSplitter.ChunkIterator itr = FUZZ_SPLITTER.splitIterator(bytes);
            while (itr.hasNext() && !runner.failed) {
                ByteBuf piece = next(bytes, itr);
                runner.offer(!itr.hasNext() ? new DefaultLastHttpContent(piece) : new DefaultHttpContent(piece));
            }
        }
        logStackTraces = false;
    }

    protected abstract InterfaceHttpPostRequestDecoder createNormal();

    protected abstract InterfaceHttpPostRequestDecoder createLegacy();

    private class Runner implements Closeable {

        final InterfaceHttpPostRequestDecoder a;
        final InterfaceHttpPostRequestDecoder b;
        boolean failed = false;

        private Runner() {
            a = createLegacy();
            b = createNormal();
        }

        void offer(HttpContent content) {
            Exception exc1 = null;
            try {
                a.offer(content.copy());
            } catch (Exception e) {
                if (logStackTraces) {
                    e.printStackTrace();
                }
                exc1 = e;
                failed = true;
            }
            Exception exc2 = null;
            try {
                b.offer(content);
            } catch (Exception e) {
                if (logStackTraces) {
                    e.printStackTrace();
                }
                exc2 = e;
                failed = true;
            }
            Assertions.assertEquals(exc1 == null, exc2 == null);
            if (exc1 != null) {
                if (exc2.getClass() == FormDecoderException.class) {
                    Assertions.assertEquals(HttpPostRequestDecoder.ErrorDataDecoderException.class, exc1.getClass());
                } else {
                    Assertions.assertEquals(exc1.getClass(), exc2.getClass());
                }
                String m1 = exc1.getMessage();
                String m2 = exc2.getMessage();
                m2 = simplifyExcMessage(m2);
                m1 = simplifyExcMessage(m1);
                try {
                    Assertions.assertEquals(m1, m2);
                } catch (AssertionError e) {
                    // NPE does not have consistent messages
                    boolean inconsistentMessage = false;
                    for (Class<?> cl : List.of(NullPointerException.class, ArrayIndexOutOfBoundsException.class, IndexOutOfBoundsException.class)) {
                        if ((cl.isInstance(exc1.getCause()) && cl.isInstance(exc2.getCause())) ||
                                (cl.isInstance(exc1) && cl.isInstance(exc2))) {
                            inconsistentMessage = true;
                            break;
                        }
                    }
                    if (!inconsistentMessage) {
                        exc1.printStackTrace();
                        exc2.printStackTrace();
                        throw e;
                    }
                }
            } else {
                compare(a, b);
            }
        }

        private String simplifyExcMessage(String m) {
            // old impl contained the string and index
            if (m != null && m.startsWith("Invalid hex byte")) {
                m = "Invalid hex byte";
            }
            if (m != null && m.startsWith("Bad string")) {
                m = "Bad string";
            }
            return m;
        }

        @Override
        public void close() {
            a.destroy();
            b.destroy();
        }
    }

    private static void compare(InterfaceHttpPostRequestDecoder a, InterfaceHttpPostRequestDecoder b) {
        HttpData partialA = (HttpData) a.currentPartialHttpData();
        HttpData partialB = (HttpData) b.currentPartialHttpData();
        Assertions.assertEquals(partialA == null, partialB == null);
        if (partialA != null) {
            compare(partialA, partialB);
        }
        Assertions.assertEquals(bodyListHttpData(a).size(), bodyListHttpData(b).size());
        for (int i = 0; i < bodyListHttpData(a).size(); i++) {
            compare((HttpData) bodyListHttpData(a).get(i), (HttpData) bodyListHttpData(b).get(i));
        }
    }

    private static List<?> bodyListHttpData(InterfaceHttpPostRequestDecoder decoder) {
        if (decoder instanceof HttpPostMultipartRequestDecoder) {
            return ((HttpPostMultipartRequestDecoder) decoder).bodyListHttpData;
        } else if (decoder instanceof HttpPostMultipartRequestDecoderLegacy) {
            return ((HttpPostMultipartRequestDecoderLegacy) decoder).bodyListHttpData;
        } else if (decoder instanceof HttpPostStandardRequestDecoder) {
            return ((HttpPostStandardRequestDecoder) decoder).bodyListHttpData;
        } else if (decoder instanceof HttpPostStandardRequestDecoderLegacy) {
            return ((HttpPostStandardRequestDecoderLegacy) decoder).bodyListHttpData;
        }
        throw new AssertionError();
    }

    private static void compare(HttpData a, HttpData b) {
        Assertions.assertEquals(a.getName(), b.getName());
        Assertions.assertEquals(a.getHttpDataType(), b.getHttpDataType());
        Assertions.assertEquals(a.getCharset(), b.getCharset());
        Assertions.assertEquals(a.definedLength(), b.definedLength());
        Assertions.assertEquals(a.length(), b.length());
        Assertions.assertEquals(a.isCompleted(), b.isCompleted());
        Assertions.assertEquals(a.getMaxSize(), b.getMaxSize());
        if (a instanceof FileUpload) {
            Assertions.assertEquals(((FileUpload) a).getContentType(), ((FileUpload) b).getContentType());
            Assertions.assertEquals(((FileUpload) a).getContentTransferEncoding(), ((FileUpload) b).getContentTransferEncoding());
            Assertions.assertEquals(((FileUpload) a).getFilename(), ((FileUpload) b).getFilename());
        }
        try {
            Assertions.assertEquals(a.getByteBuf(), b.getByteBuf());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
