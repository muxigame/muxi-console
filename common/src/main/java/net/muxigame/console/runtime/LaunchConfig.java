package net.muxigame.console.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LaunchConfig(Path javaExecutable, List<Path> classpath, Path core, Path content,
                           Path systemDirectory, Path saveDirectory, Map<String, String> options,String platform) {
    public LaunchConfig {
        Objects.requireNonNull(javaExecutable); Objects.requireNonNull(core);
        Objects.requireNonNull(systemDirectory); Objects.requireNonNull(saveDirectory);
        classpath = List.copyOf(classpath); options = Map.copyOf(options);
        if (classpath.isEmpty()) throw new IllegalArgumentException("Worker classpath is empty");
        if (options.size() > 256) throw new IllegalArgumentException("Too many core options");
        if(platform==null||!platform.matches("[a-z0-9_]{0,16}"))throw new IllegalArgumentException("Invalid runtime platform");
    }
    public LaunchConfig(Path javaExecutable,List<Path> classpath,Path core,Path content,Path systemDirectory,Path saveDirectory,Map<String,String> options){this(javaExecutable,classpath,core,content,systemDirectory,saveDirectory,options,"");}
    public LaunchConfig(Path javaExecutable, List<Path> classpath, Path core, Path content,
                        Path systemDirectory, Path saveDirectory) {
        this(javaExecutable, classpath, core, content, systemDirectory, saveDirectory, Map.of());
    }
}
