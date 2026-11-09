package net.muxigame.console.ops;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.domain.storage.*;

/** Explicit maintenance entry point: local objects to one private prefix, never an object-body GET. */
public final class ContentMigration {
    private ContentMigration(){}
    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>3||args.length==3&&!args[2].equals("--apply"))throw new IllegalArgumentException("Usage: ContentMigration <external-private-config> <world-content-directory> [--apply]");
        Path config=Path.of(args[0]).toAbsolutePath().normalize(),root=Path.of(args[1]).toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IOException("Expected an existing ordinary world content directory");
        boolean apply=args.length==3;ContentStore content=new ContentStore(root);List<Path> objects;
        try(var files=Files.walk(root,2)){objects=files.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.rom")).sorted().toList();}
        long bytes=0;for(Path file:objects){String hash=file.getFileName().toString().substring(0,64);Path expected=root.resolve(hash.substring(0,2)).resolve(hash+".rom");if(!file.equals(expected)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException("Unsafe content object location");RomDescriptor descriptor=content.describe(hash);bytes=Math.addExact(bytes,descriptor.size());}
        if(!apply){System.out.println("PLAN localObjects="+objects.size()+" bytes="+bytes+" apply=false localFilesRetained=true");return;}
        ObjectStorage storage=ObjectStorages.load(config).orElseThrow(()->new IOException("An existing external cloud configuration is required for migration"));
        int uploaded=0,reused=0;for(Path file:objects){String hash=file.getFileName().toString().substring(0,64);RomDescriptor descriptor=content.describe(hash);
            boolean created=storage.putExisting(file,hash,descriptor.platform().id());
            ObjectStorage.Metadata confirmed=storage.head(hash).orElseThrow(()->new IOException("Object storage did not confirm migration"));
            if(confirmed.size()!=descriptor.size()||!hash.equals(confirmed.sha256())||!descriptor.platform().id().equals(confirmed.platform()))throw new IOException("Migration object metadata differs");
            content.registerRemote(descriptor);if(created)uploaded++;else reused++;
            System.out.println("MIGRATED hash="+hash+" bytes="+descriptor.size()+" uploaded="+created);
        }
        System.out.println("COMPLETE objects="+objects.size()+" uploaded="+uploaded+" reused="+reused+" bytes="+bytes+" objectBodyGets=0 localFilesRetained=true");
    }
}
