package net.muxigame.console.domain;

import net.muxigame.console.domain.storage.ObjectStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

/**
 * Server-owned direct-upload transactions. Call begin/finish on an I/O executor, never the MC thread.
 * No staging file, object-body GET, server-side ROM parsing or claimed body-hash verification.
 */
public final class CloudUploads implements AutoCloseable {
    public static final long IDLE_TIMEOUT_MILLIS = 180_000;
    public static final long ABSOLUTE_TIMEOUT_MILLIS = 7_200_000;
    public static final int MAX_SESSIONS = 16;
    private final ContentStore store;
    private final ObjectStorage storage;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private long reservedBytes;
    private boolean closed;

    private static final class Pending {
        final UUID id = UUID.randomUUID(), actor;
        final RomDescriptor descriptor;
        final long created, reservation;
        final boolean reused;
        final ObjectStorage.Multipart.Upload multipart;
        boolean merged;
        long lastActivity;
        Pending(UUID actor, RomDescriptor descriptor, boolean reused, long reservation, long now, ObjectStorage.Multipart.Upload multipart) {
            this.actor = actor; this.descriptor = descriptor; this.reused = reused; this.reservation = reservation;
            this.created = now; this.lastActivity = now;
            this.multipart = multipart;
        }
        UploadManager.Status status() { return new UploadManager.Status(id, reused || merged ? descriptor.size() : 0, descriptor.size(), true); }
    }

    public CloudUploads(ContentStore store, ObjectStorage storage) {
        this.store = Objects.requireNonNull(store); this.storage = Objects.requireNonNull(storage);
    }
    public synchronized int activeCount() { return pending.size(); }

    public synchronized UploadManager.Status begin(UUID actor, UploadManager.Request request, long nowMillis) throws IOException {
        checkOpen(); checkTime(nowMillis); Objects.requireNonNull(actor); Objects.requireNonNull(request);
        if (!request.rightsConfirmed()) throw new DomainException("rights_required", "Confirm that you may upload and share this ROM");
        RomInspector.selectPlatform(request.filename(), request.platform());
        if (request.size() < request.platform().minBytes() || request.size() > Math.min(RomInspector.MAX_ROM_BYTES, request.platform().maxBytes()))
            throw new DomainException("size", "ROM size exceeds upload or platform limit");
        RomDescriptor descriptor = new RomDescriptor(request.platform(), request.sha256(), request.size(), request.filename(), "client_declared");
        expire(nowMillis);
        if (pending.size() >= MAX_SESSIONS || pending.values().stream().anyMatch(p -> p.actor.equals(actor)))
            throw new DomainException("limit", "Too many pending uploads");
        boolean registered = store.has(descriptor.sha256());
        if (registered) match(store.describe(descriptor.sha256()), descriptor);
        long reservation = registered ? 0 : descriptor.size();
        if (reservation > 0) {
            long used = store.usedBytes();
            long available = store.quotaBytes() - Math.min(used, store.quotaBytes());
            if (reservation > available - Math.min(reservedBytes, available))
                throw new DomainException("quota", "Content store is full");
        }

        // A HEAD failure is not a missing object and must never authorize a new upload silently.
        var remote = storage.head(descriptor.sha256());
        if (remote.isPresent()) {
            match(remote.get(), descriptor);
            store.registerRemote(descriptor); reservation = 0;
        }
        ObjectStorage.Multipart.Upload multipart = null;
        if (remote.isEmpty() && descriptor.size() > ObjectStorage.MAX_SINGLE_PUT_BYTES)
            multipart = multipart().start(descriptor.sha256(), descriptor.size(), descriptor.platform().id());
        Pending upload = new Pending(actor, descriptor, remote.isPresent(), reservation, nowMillis, multipart);
        pending.put(upload.id, upload); reservedBytes = Math.addExact(reservedBytes, reservation);
        return upload.status();
    }

    /** Observation does not extend the transaction's idle lease. */
    public synchronized UploadManager.Status status(UUID actor, UUID id, long nowMillis) throws IOException {
        checkOpen(); return require(actor, id, nowMillis).status();
    }
    /** Lease renewal is bounded by the immutable two-hour creation deadline. */
    public synchronized UploadManager.Status touch(UUID actor, UUID id, long nowMillis) throws IOException {
        checkOpen(); Pending upload = require(actor, id, nowMillis); upload.lastActivity = nowMillis; return upload.status();
    }
    public synchronized ObjectStorage.Ticket uploadTicket(UUID actor, UUID id, long nowMillis) throws IOException {
        checkOpen(); Pending upload = require(actor, id, nowMillis);
        if (upload.reused) throw new DomainException("complete", "Existing content needs no upload");
        if (upload.multipart != null) throw new DomainException("multipart", "This file requires direct multipart upload");
        ObjectStorage.Ticket result = storage.upload(upload.descriptor.sha256(), upload.descriptor.size(), upload.descriptor.platform().id());
        upload.lastActivity = nowMillis;
        return result;
    }

    /** Small immutable plan only, not hundreds of presigned URLs. Does not renew the lease. */
    public synchronized Optional<ObjectStorage.Multipart.Upload> multipartPlan(UUID actor, UUID id, long nowMillis) throws IOException {
        checkOpen(); return Optional.ofNullable(require(actor,id,nowMillis).multipart);
    }
    public synchronized ObjectStorage.Ticket partTicket(UUID actor, UUID id, int number, long nowMillis) throws IOException {
        checkOpen(); Pending upload=require(actor,id,nowMillis); requireMultipart(upload);
        ObjectStorage.Ticket ticket=multipart().partTicket(upload.multipart,number); upload.lastActivity=nowMillis; return ticket;
    }
    /** Actual cloud metadata, useful after an interrupted PUT; no object-body GET. */
    public synchronized List<ObjectStorage.Multipart.Part> parts(UUID actor, UUID id, long nowMillis) throws IOException {
        checkOpen(); Pending upload=require(actor,id,nowMillis); requireMultipart(upload);
        return multipart().list(upload.multipart);
    }
    public synchronized UploadManager.Status complete(UUID actor, UUID id, List<ObjectStorage.Multipart.Part> receipts, long nowMillis) throws IOException {
        checkOpen(); Pending upload=require(actor,id,nowMillis); requireMultipart(upload);
        if(!upload.merged){match(multipart().complete(upload.multipart,receipts),upload.descriptor);upload.merged=true;}
        upload.lastActivity=nowMillis;return upload.status();
    }

    public synchronized RomDescriptor finish(UUID actor, UUID id, long nowMillis) throws IOException {
        checkOpen(); Pending upload = require(actor, id, nowMillis);
        try {
            if(upload.multipart!=null&&!upload.merged)throw new DomainException("incomplete","Complete every multipart upload part before issuing the cartridge");
            ObjectStorage.Metadata remote = storage.head(upload.descriptor.sha256())
                    .orElseThrow(() -> new DomainException("incomplete", "Object storage has not confirmed the complete upload"));
            match(remote, upload.descriptor);
            return store.registerRemote(upload.descriptor);
        } finally { cancelMultipart(upload); remove(upload); }
    }
    public synchronized void abort(UUID actor, UUID id) throws IOException {
        checkOpen(); Pending upload = pending.get(id);
        if (upload == null || !upload.actor.equals(actor)) throw new DomainException("upload", "Upload is not available to this actor");
        cancelMultipart(upload); remove(upload);
    }
    /** Server-internal only: caller first verifies the exact writer job/token and queues this before a new begin. */
    public synchronized int abortActor(UUID actor) throws IOException {
        checkOpen(); Objects.requireNonNull(actor); int count = 0;
        for (Pending upload : new ArrayList<>(pending.values())) {
            if (upload.actor.equals(actor)) { cancelMultipart(upload); remove(upload); count++; }
        }
        return count;
    }
    public synchronized int expire(long nowMillis) throws IOException {
        checkOpen(); checkTime(nowMillis); int count = 0;
        for (Pending upload : new ArrayList<>(pending.values())) {
            if (expired(upload, nowMillis)) { cancelMultipart(upload); remove(upload); count++; }
        }
        return count;
    }
    @Override public synchronized void close() throws IOException {
        closed = true; IOException failure=null;
        // Keep failed aborts for a subsequent close retry; never claim an orphan was removed on error.
        for(Pending upload:new ArrayList<>(pending.values()))try{cancelMultipart(upload);remove(upload);}catch(IOException aborted){if(failure==null)failure=new IOException("Cloud multipart cleanup remains pending");}
        if(failure!=null)throw failure;
    }

    private Pending require(UUID actor, UUID id, long now) throws IOException {
        checkTime(now);
        Pending upload = pending.get(id);
        if (upload == null || !upload.actor.equals(actor)) throw new DomainException("upload", "Upload is not available to this actor");
        if (now < upload.lastActivity) throw new DomainException("clock", "Upload time moved backwards");
        if (expired(upload, now)) { cancelMultipart(upload); remove(upload); throw new DomainException("expired", "Upload timed out"); }
        return upload;
    }
    private static boolean expired(Pending upload, long now) {
        return now >= upload.lastActivity && now - upload.lastActivity >= IDLE_TIMEOUT_MILLIS
                || now >= upload.created && now - upload.created >= ABSOLUTE_TIMEOUT_MILLIS;
    }
    private void remove(Pending upload) { if (pending.remove(upload.id) != null) reservedBytes -= upload.reservation; }
    private ObjectStorage.Multipart multipart() { return storage.multipart().orElseThrow(()->new DomainException("multipart","Configured storage does not support large-file multipart uploads")); }
    private static void requireMultipart(Pending upload) { if(upload.multipart==null)throw new DomainException("multipart","This upload does not use multipart transport"); }
    private void cancelMultipart(Pending upload)throws IOException { if(upload.multipart!=null&&!upload.merged)multipart().abort(upload.multipart); }
    private static void match(ObjectStorage.Metadata metadata, RomDescriptor descriptor) {
        if (metadata.size() != descriptor.size() || !descriptor.sha256().equals(metadata.sha256())
                || !descriptor.platform().id().equals(metadata.platform()))
            throw new DomainException("content", "Object storage metadata differs from the upload declaration");
    }
    private static void match(RomDescriptor prior, RomDescriptor next) {
        if (prior.platform() != next.platform() || prior.size() != next.size())
            throw new DomainException("content", "Existing content metadata differs from the upload declaration");
    }
    private void checkOpen() { if (closed) throw new DomainException("closed", "Cloud upload manager is closed"); }
    private static void checkTime(long now) { if (now < 0) throw new DomainException("clock", "Negative upload time"); }
}
