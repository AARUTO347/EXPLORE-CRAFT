import arc.*;
import mindustry.core.*;
import mindustry.net.Packets.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

public class AssetStreamCancellationTests{
    @BeforeAll
    static void launch(){
        ApplicationTests.launchApplication();
    }

    @Test
    void cancelledAssetReaderDoesNotQueueFailureOrWorldRequest() throws Exception{
        var client = new NetClient();
        var originalApp = Core.app;
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Thread> reader = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Core.app = (Application)Proxy.newProxyInstance(Application.class.getClassLoader(), new Class[]{Application.class}, (proxy, method, args) -> {
            if(method.getName().equals("post")) callbacks.incrementAndGet();
            return null;
        });
        try{
            StreamBegin begin = new StreamBegin();
            begin.total = 4;
            begin.incrementalStream = new InputStream(){
                @Override
                public int read() throws IOException{
                    reader.set(Thread.currentThread());
                    started.countDown();
                    throw new ClosedChannelException();
                }
            };
            var method = NetClient.class.getDeclaredMethod("receiveAssets", StreamBegin.class);
            method.setAccessible(true);
            method.invoke(client, begin);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            reader.get().join(5000);
            assertFalse(reader.get().isAlive());
            assertEquals(0, callbacks.get());
        }finally{
            Core.app = originalApp;
        }
    }
}
