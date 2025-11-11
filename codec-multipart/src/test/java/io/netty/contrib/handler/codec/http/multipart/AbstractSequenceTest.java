package io.netty.contrib.handler.codec.http.multipart;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;

public abstract class AbstractSequenceTest extends AbstractFuzzTest {
    protected abstract PostBodyDecoder createDecoder();

    @FuzzTest
    @MultipartFuzzTest
    public void testSequence(byte[] input) {
        try (PostBodyDecoder decoder = createDecoder()) {
            decoder.add(Unpooled.wrappedBuffer(input));

            //noinspection InfiniteLoopStatement
            while (true) {
                Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, next(decoder));
                checkField(decoder, false);
            }
        } catch (HttpPostRequestDecoder.ErrorDataDecoderException | HttpPostRequestDecoder.TooManyFormFieldsException ignored) {
        }
    }

    private void checkField(PostBodyDecoder decoder, boolean mixed) {
        PostBodyDecoder.Event event;
        do {
            event = next(decoder);
        } while (event == PostBodyDecoder.Event.HEADER);
        if (event == PostBodyDecoder.Event.HEADERS_COMPLETE) {
            do {
                event = next(decoder);
            } while (event == PostBodyDecoder.Event.CONTENT);
        } else {
            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_MIXED, event);
            Assertions.assertFalse(mixed);
            while (true) {
                event = next(decoder);
                if (event != PostBodyDecoder.Event.BEGIN_FIELD) {
                    break;
                }
                checkField(decoder, mixed);
            }
        }
        Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, event);
    }

    private static PostBodyDecoder.Event next(PostBodyDecoder decoder) {
        PostBodyDecoder.Event event = decoder.next();
        if (event == null) {
            // this is ok, caught above
            throw new HttpPostRequestDecoder.ErrorDataDecoderException("Expected eof");
        }
        return event;
    }
}
