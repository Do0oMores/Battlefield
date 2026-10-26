package top.mores.battlefield.bridge;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.*;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import top.mores.battlefield.protocol.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.battlefield.protocol.BridgeProtocol.*;

/** Actual reflection transport under a controlled Bukkit scheduler, without pretending to be Mohist. */
class BridgeLifecycleTest {
    public interface Wire {
        long connect(int protocol,Function<String,CompletableFuture<String>> validator);
        void disconnect(long generation);
        String health();
        CompletableFuture<String> request(String json);
    }
    static volatile Object installed;
    static final Queue<Runnable> main=new ArrayDeque<>();
    static <T> T proxy(Class<T> type,InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler)); }
    static Object defaultValue(Class<?> type) {
        if(!type.isPrimitive() || type==void.class)return null;
        if(type==boolean.class)return false; if(type==long.class)return 0L; if(type==double.class)return 0D; if(type==float.class)return 0F; return 0;
    }
    @BeforeAll static void bukkit() {
        var manager=proxy(PluginManager.class,(p,m,a)->m.getName().equals("getPlugin")?installed:defaultValue(m.getReturnType()));
        var scheduler=proxy(BukkitScheduler.class,(p,m,a)->{ if(m.getName().equals("runTask") && a[1] instanceof Runnable task) main.add(task); return defaultValue(m.getReturnType()); });
        var server=proxy(Server.class,(p,m,a)->switch(m.getName()) {
            case "getPluginManager"->manager; case "getScheduler"->scheduler; case "getLogger"->java.util.logging.Logger.getLogger("bridge-test");
            case "getName","getVersion","getBukkitVersion"->"test"; default->defaultValue(m.getReturnType());
        }); Bukkit.setServer(server);
    }
    @AfterAll static void reset() throws Exception { var field=Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null,null); }
    @BeforeEach void before() { installed=null;main.clear(); }
    void drain() { while(!main.isEmpty())main.remove().run(); }
    static class Fake {
        int api=3; boolean ready=true; int connects,disconnects; long generation;
        CompletableFuture<String> response=new CompletableFuture<>();
        Object plugin() { return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Plugin.class,Wire.class},(p,m,a)->switch(m.getName()) {
            case "connect"->{connects++;yield ++generation;}
            case "disconnect"->{if((Long)a[0]==generation)disconnects++;yield null;}
            case "health"->"{\"apiVersion\":"+api+",\"backendProtocol\":2,\"bridgeProtocol\":1,\"generation\":"+generation+",\"ready\":"+ready+"}";
            case "request"->response; case "getName"->"BattlefieldBridge"; case "isEnabled"->true;
            case "hashCode"->System.identityHashCode(p); case "equals"->p==a[0]; default->defaultValue(m.getReturnType());
        }); }
    }
    BFBackpackServiceBridge service() { return new BFBackpackServiceBridge(s->CompletableFuture.completedFuture(compatibility("VERIFIED","OK","test"))); }
    @Test void missingPluginIsExplicitlyUnavailable() {
        try(var bridge=service()) { bridge.probe(); assertFalse(bridge.ready()); assertTrue(bridge.health().contains("not installed")); assertTrue(bridge.request(UUID.randomUUID(),SoldierClass.ASSAULT,"deployment").join().contains("NOT_READY")); }
    }
    @Test void asynchronousStartupThenRegistrationAndRequestsDoNotBlockGameThread() {
        var plugin=new Fake();plugin.ready=false;installed=plugin.plugin();
        try(var bridge=service()) {
            bridge.probe();assertEquals(0,plugin.connects);drain();assertTrue(bridge.health().contains("initializing"));
            plugin.ready=true;bridge.probe();drain();assertTrue(bridge.health().startsWith("OK"));assertEquals(1,plugin.connects);
            var request=bridge.request(UUID.randomUUID(),SoldierClass.MEDIC,"loadout");drain();assertFalse(request.isDone());
            plugin.response.complete(failure("OK","test"));assertEquals("OK",text(parse(request.join()),"code"));
        } drain();assertEquals(1,plugin.disconnects);
    }
    @Test void incompatibleApiIsRejectedBeforeAnyRequest() {
        var plugin=new Fake();plugin.api=4;installed=plugin.plugin();try(var bridge=service()) {bridge.probe();drain();assertFalse(bridge.ready());assertEquals("API_INCOMPATIBLE",bridge.health());}
    }
    @Test void uninstallCancelsPendingAndLateCompletionCannotReviveConnection() {
        var plugin=new Fake();installed=plugin.plugin();try(var bridge=service()) {
            bridge.probe();drain();var request=bridge.request(UUID.randomUUID(),SoldierClass.ASSAULT,"loadout");drain();installed=null;bridge.probe();
            assertTrue(request.join().contains("NOT_READY"));plugin.response.complete(failure("OK","late"));assertTrue(request.join().contains("NOT_READY"));
        }
    }
    @Test void pluginReloadReplacesGenerationAndOldResultsCannotAuthorizeNewSession() {
        var first=new Fake();installed=first.plugin();try(var bridge=service()) {
            bridge.probe();drain();var pending=bridge.request(UUID.randomUUID(),SoldierClass.RECON,"loadout");drain();
            var next=new Fake();installed=next.plugin();bridge.probe();drain();first.response.complete(failure("OK","old"));
            assertTrue(pending.join().contains("NOT_READY"));assertEquals(1,next.connects);assertTrue(bridge.health().startsWith("OK"));
        }drain();
    }
    @Test void controlledTimeoutAndShutdownProduceFailureWithoutWaiting() {
        var plugin=new Fake();installed=plugin.plugin();var bridge=service();bridge.probe();drain();
        var request=bridge.request(UUID.randomUUID(),SoldierClass.SUPPORT,"loadout");drain();plugin.response.completeExceptionally(new TimeoutException("controlled deadline"));
        assertTrue(request.join().contains("VALIDATION_TIMEOUT"));bridge.close();drain();assertFalse(bridge.ready());assertEquals(1,plugin.disconnects);
    }
}
