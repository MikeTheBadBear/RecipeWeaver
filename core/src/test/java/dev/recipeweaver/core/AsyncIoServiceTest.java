package dev.recipeweaver.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class AsyncIoServiceTest {
    @Test
    void tasksAndLogEmissionRunInOrderOffTheCallerThread() {
        Logger logger = Logger.getLogger("RecipeWeaverAsyncIoTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                order.add("log:" + record.getMessage() + ":" + Thread.currentThread().getName());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        long caller = Thread.currentThread().threadId();
        try (AsyncIoService io = new AsyncIoService(logger)) {
            long worker = io.submit(() -> {
                order.add("first");
                return Thread.currentThread().threadId();
            }).join();
            io.log(Level.INFO, "hello");
            io.submit(() -> {
                order.add("last");
                return null;
            });
            io.flush().join();
            assertNotEquals(caller, worker);
            assertEquals("first", order.get(0));
            assertTrue(order.get(1).startsWith("log:hello:RecipeWeaver-IO"));
            assertEquals("last", order.get(2));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void failedLogHandlerIsReportedWithoutPoisoningOrderedStorage() {
        Logger logger = Logger.getLogger("RecipeWeaverAsyncIoLogFailure-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        AtomicReference<String> publishThread = new AtomicReference<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                publishThread.set(Thread.currentThread().getName());
                throw new IllegalStateException("log handler failed");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream originalError = System.err;
        PrintStream capturedError = new PrintStream(errors, true, StandardCharsets.UTF_8);
        System.setErr(capturedError);
        try (AsyncIoService io = new AsyncIoService(logger)) {
            io.log(Level.WARNING, "handler fault");
            assertEquals("saved", io.submitAfterSuccessfulWrites(() -> "saved").join());
            io.flush().join();
            assertEquals("RecipeWeaver-IO", publishThread.get());
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("log handler failed"));
        } finally {
            System.setErr(originalError);
            capturedError.close();
            logger.removeHandler(handler);
        }
    }

    @Test
    void closeWaitsForQueuedWriteAtShutdown() throws Exception {
        Logger logger = Logger.getLogger("RecipeWeaverAsyncIoClose-" + System.nanoTime());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AsyncIoService io = new AsyncIoService(logger);
        var write = io.submit(() -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Write timed out");
            return "durable";
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> {
            try { io.close(); }
            catch (Throwable ex) { closeFailure.set(ex); }
        }, "RecipeWeaver-Test-Disable");
        closer.start();
        assertTrue(closer.isAlive());
        release.countDown();
        closer.join(5000);
        assertTrue(!closer.isAlive());
        assertEquals(null, closeFailure.get());
        assertEquals("durable", write.get(5, TimeUnit.SECONDS));
    }

    @Test
    void barrierReportsPriorFailureAndCanBeReused() {
        Logger logger = Logger.getLogger("RecipeWeaverAsyncIoFailure-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        try (AsyncIoService io = new AsyncIoService(logger)) {
            assertThrows(CompletionException.class, () ->
                    io.submit(() -> { throw new IOException("disk failed"); }).join());
            assertThrows(CompletionException.class, () -> io.flush().join());
            io.flush().join();
        }
    }
}
