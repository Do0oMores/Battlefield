package top.mores.battlefield.bukkit;

import org.junit.jupiter.api.Test;
import org.bukkit.plugin.PluginDescriptionFile;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class BridgeClassLoaderTest {
    @Test void twoIsolatedLoadersUseIdenticalJdkBoundaryTypesButDifferentApiClasses() throws Exception {
        URL[] classpath=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(p->{try{return Path.of(p).toUri().toURL();}catch(Exception e){throw new IllegalArgumentException(e);}}).toArray(URL[]::new);
        try(var first=new URLClassLoader(classpath,ClassLoader.getPlatformClassLoader());var second=new URLClassLoader(classpath,ClassLoader.getPlatformClassLoader())) {
            var a=first.loadClass("top.mores.battlefield.bukkit.BridgeLifecycle"); var b=second.loadClass("top.mores.battlefield.bukkit.BridgeLifecycle");
            assertNotSame(a,b);
            assertEquals(long.class,a.getMethod("connect",int.class,Function.class).getReturnType());
            assertEquals(CompletableFuture.class,b.getMethod("request",String.class).getReturnType());
            var apiA=first.loadClass("top.mores.bFBackpack.api.BFBackpackAPI"); var apiB=second.loadClass("top.mores.bFBackpack.api.BFBackpackAPI"); assertNotSame(apiA,apiB);
            assertEquals(String.class,b.getMethod("health").getReturnType());
        }
    }
    @Test void descriptorDeclaresHardDependencyWithoutPackagingApi() throws Exception {
        try(var input=getClass().getResourceAsStream("/plugin.yml")) {
            var descriptor=new PluginDescriptionFile(input); assertEquals(List.of("BFBackpack"),descriptor.getDepend()); assertEquals("BattlefieldBridge",descriptor.getName());
        }
    }
}
