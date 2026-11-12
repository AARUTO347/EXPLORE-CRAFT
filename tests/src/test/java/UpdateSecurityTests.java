import arc.files.*;
import arc.func.*;
import arc.util.*;
import com.sun.net.httpserver.*;
import mindustry.net.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

public class UpdateSecurityTests{
    @TempDir
    Path directory;

    HttpServer server;
    String url;
    byte[] payload;

    @BeforeEach
    void serve() throws IOException{
        payload = new byte[16384];
        for(int i = 0; i < payload.length; i++) payload[i] = (byte)i;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/update", exchange -> {
            try(exchange){
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        server.createContext("/missing", exchange -> {
            try(exchange){
                exchange.sendResponseHeaders(404, -1);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop(){
        server.stop(0);
    }

    @Test
    void verifiedDownloadCompletesAndReleasesFile() throws Exception{
        Fi dest = new Fi(directory.resolve("update.jar").toFile());
        dest.writeString("previous version");
        var result = download(url + "/update", hash(), dest, () -> false);
        assertTrue(result.completed);
        assertNull(result.failure);
        assertArrayEquals(payload, dest.readBytes());
        Path renamed = directory.resolve("renamed.jar");
        Files.move(dest.file().toPath(), renamed);
        assertTrue(Files.exists(renamed));
    }

    @Test
    void digestMismatchRemovesFileWithoutCompletion() throws Exception{
        Fi dest = new Fi(directory.resolve("invalid.jar").toFile());
        var result = download(url + "/update", "0".repeat(64), dest, () -> false);
        assertFalse(result.completed);
        assertInstanceOf(IOException.class, result.failure);
        assertTrue(result.failure.getMessage().contains("digest mismatch"));
        assertFalse(dest.exists());
        dest.writeString("file is unlocked");
        assertTrue(dest.delete());
    }

    @Test
    void failedRetryPreservesExistingArtifact() throws Exception{
        Fi dest = new Fi(directory.resolve("existing.jar").toFile());
        dest.writeBytes(payload);
        for(String digest : new String[]{null, "0".repeat(64)}){
            var result = download(url + "/update", digest, dest, () -> false);
            assertFalse(result.completed);
            assertInstanceOf(IOException.class, result.failure);
            assertArrayEquals(payload, dest.readBytes());
        }
        try(var files = Files.list(directory)){
            assertEquals(0, files.filter(path -> path.toString().endsWith(".part")).count());
        }
    }

    @Test
    void missingDigestFailsClosed() throws Exception{
        Fi dest = new Fi(directory.resolve("missing-digest.jar").toFile());
        var result = download(url + "/update", null, dest, () -> false);
        assertFalse(result.completed);
        assertInstanceOf(IOException.class, result.failure);
        assertFalse(dest.exists());
    }

    @Test
    void httpErrorDoesNotProduceAnUpdate() throws Exception{
        Fi dest = new Fi(directory.resolve("missing.jar").toFile());
        var result = download(url + "/missing", hash(), dest, () -> false);
        assertFalse(result.completed);
        assertInstanceOf(IOException.class, result.failure);
        assertTrue(result.failure.getMessage().contains("HTTP 404"));
        assertFalse(dest.exists());
    }

    @Test
    void cancellationRemovesPartialFile() throws Exception{
        Fi dest = new Fi(directory.resolve("canceled.jar").toFile());
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean canceled = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var originalExecutor = mindustry.Vars.mainExecutor;
        var executor = Executors.newSingleThreadExecutor();
        try{
            mindustry.Vars.mainExecutor = executor;
            downloadMethod().invoke(new BeControl(), url + "/update", hash(), dest, (Intc)size -> {}, (Floatc)progress -> canceled.set(true),
                (Boolp)canceled::get, (Runnable)() -> completed.set(true), (Cons<Throwable>)failure::set);
            executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertTrue(canceled.get());
            assertFalse(completed.get());
            assertNull(failure.get());
            assertFalse(dest.exists());
        }finally{
            mindustry.Vars.mainExecutor = originalExecutor;
            executor.shutdownNow();
        }
    }

    private DownloadResult download(String uri, String digest, Fi dest, Boolp cancel) throws Exception{
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        downloadMethod().invoke(new BeControl(), uri, digest, dest, (Intc)size -> {}, (Floatc)progress -> {}, cancel,
            (Runnable)() -> {
                completed.set(true);
                finished.countDown();
            }, (Cons<Throwable>)error -> {
                failure.set(error);
                finished.countDown();
            });
        assertTrue(finished.await(15, TimeUnit.SECONDS), "Update download did not finish.");
        return new DownloadResult(completed.get(), failure.get());
    }

    private java.lang.reflect.Method downloadMethod() throws NoSuchMethodException{
        var method = BeControl.class.getDeclaredMethod("download", String.class, String.class, Fi.class,
            Intc.class, Floatc.class, Boolp.class, Runnable.class, Cons.class);
        method.setAccessible(true);
        return method;
    }

    private String hash() throws NoSuchAlgorithmException{
        return Strings.bytesToHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    record DownloadResult(boolean completed, Throwable failure){
    }
}
