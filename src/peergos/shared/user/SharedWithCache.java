package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class SharedWithCache {

    public enum Access { READ, WRITE }

    private static final String DIR_CACHE_FILENAME = "sharedWith.cbor";
    private static final String CACHE_BASE_NAME = "outbound";
    private static final Path CACHE_BASE = Paths.get(CapabilityStore.CAPABILITY_CACHE_DIR, CACHE_BASE_NAME);

    private final Function<Path, CompletableFuture<Optional<FileWrapper>>> retriever;
    private final Path cacheBase;
    private final String ourname;
    private final NetworkAccess network;
    private final Crypto crypto;

    public SharedWithCache(Function<Path, CompletableFuture<Optional<FileWrapper>>> retriever,
                           String ourname,
                           NetworkAccess network,
                           Crypto crypto) {
        this.retriever = retriever;
        this.cacheBase = Paths.get("/" + ourname).resolve(CACHE_BASE);
        this.ourname = ourname;
        this.network = network;
        this.crypto = crypto;
    }

    private static Path canonicalise(Path p) {
        return p.isAbsolute() ? p : Paths.get("/").resolve(p);
    }

    private CompletableFuture<Optional<SharedWithState>> retrieve(Path dir) {
        return retrieveWithFile(dir).thenApply(opt -> opt.map(p -> p.right));
    }

    private CompletableFuture<Optional<Pair<FileWrapper, SharedWithState>>> retrieveWithFile(Path dir) {
        return retriever.apply(cacheBase.resolve(dir).resolve(DIR_CACHE_FILENAME))
                .thenCompose(opt -> opt.isEmpty() ?
                        Futures.of(Optional.empty()) :
                        parseCacheFile(opt.get())
                                .thenApply(s -> new Pair<>(opt.get(), s))
                                .thenApply(Optional::of)
                );
    }

    /**
     *
     * @return root of cache
     */
    private CompletableFuture<FileWrapper> initializeCache() {
        return retriever.apply(Paths.get(ourname))
                .thenCompose(userRoot -> getOrMkdir(userRoot.get(), CapabilityStore.CAPABILITY_CACHE_DIR))
                .thenCompose(cacheRoot -> getOrMkdir(cacheRoot, CACHE_BASE_NAME)); //TODO build from outbound cap files
    }

    private CompletableFuture<FileWrapper> getOrMkdir(FileWrapper parent, String dirName) {
        return parent.getChild(dirName, crypto.hasher, network)
                .thenCompose(opt -> opt.isPresent() ?
                        Futures.of(opt.get()) :
                        parent.mkdir(dirName, network, true, crypto)
                                .thenCompose(p -> p.getChild(dirName, crypto.hasher, network))
                                .thenApply(Optional::get));
    }

    private CompletableFuture<FileWrapper> getOrMkdirs(FileWrapper parent, List<String> remaining) {
        if (remaining.isEmpty())
            return Futures.of(parent);
        return getOrMkdir(parent, remaining.get(0))
                .thenCompose(child -> getOrMkdirs(child, remaining.subList(1, remaining.size())));
    }

    private CompletableFuture<Pair<FileWrapper, SharedWithState>> retrieveWithFileOrCreate(Path dir) {
        return retriever.apply(cacheBase)
                .thenCompose(opt -> opt.isEmpty() ?
                        initializeCache() :
                        Futures.of(opt.get())
                ).thenCompose(cacheRoot -> getOrMkdirs(cacheRoot, toList(dir)))
                .thenCompose(parent -> parent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)
                        .thenCompose(fopt -> {
                            if (fopt.isPresent())
                                return parseCacheFile(fopt.get())
                                        .thenApply(c -> new Pair<>(fopt.get(), c));
                            SharedWithState empty = SharedWithState.empty();
                            byte[] raw = empty.serialize();
                            return parent.uploadOrReplaceFile(DIR_CACHE_FILENAME, AsyncReader.build(raw), raw.length,
                                    network, crypto, x -> {}, crypto.random.randomBytes(32))
                                    .thenCompose(updatedParent -> updatedParent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network))
                                    .thenApply(copt -> new Pair<>(copt.get(), empty));
                        }));
    }

    private List<String> toList(Path p) {
        return Arrays.asList(p.toString().split("/"));
    }

    private CompletableFuture<SharedWithState> parseCacheFile(FileWrapper cache) {
        return cache.getInputStream(network, crypto, x -> {})
                .thenCompose(in -> Serialize.readFully(in, cache.getSize()))
                .thenApply(CborObject::fromByteArray)
                .thenApply(SharedWithState::fromCbor);
    }

    public CompletableFuture<Map<Path, SharedWithState>> getAllDescendantShares(Path start) {
        return retriever.apply(cacheBase.resolve(start.getParent()))
                .thenCompose(opt -> {
                    if (opt.isEmpty())
                        return Futures.of(Collections.emptyMap());
                    FileWrapper parent = opt.get();
                    String filename = start.toFile().getName();
                    return parent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)
                            .thenCompose(fopt -> fopt.isEmpty() ?
                                    Futures.of(Collections.<Path, SharedWithState>emptyMap()) :
                                    parseCacheFile(fopt.get())
                                            .thenApply(c -> c.filter(filename)
                                                    .map(r -> Collections.singletonMap(start.getParent(), r))
                                                    .orElse(Collections.emptyMap()))
                            ).thenCompose(m -> parent.getChild(filename, crypto.hasher, network)
                                    .thenCompose(copt -> copt.isEmpty() ?
                                            Futures.of(m) :
                                            getAllDescendantSharesRecurse(copt.get(), start)
                                                    .thenApply(d -> merge(d, m))));
                });
    }

    private <K, V> Map<K, V> merge(Map<K, V> a, Map<K, V> b) {
        HashMap<K, V> res = new HashMap<>(a);
        res.putAll(b); // no key conflicts
        return res;
    }

    public CompletableFuture<Map<Path, SharedWithState>> getAllDescendantSharesRecurse(FileWrapper f, Path toUs) {
        if (! f.isDirectory()) {
            if (! f.getName().equals(DIR_CACHE_FILENAME))
                throw new IllegalStateException("Invalid shared with cache!");
            return parseCacheFile(f)
                    .thenApply(c -> Collections.singletonMap(toUs.getParent(), c));
        }
        return f.getChildren(crypto.hasher, network)
                .thenCompose(children -> Futures.combineAll(children.stream()
                        .map(c -> getAllDescendantSharesRecurse(c, toUs))
                        .collect(Collectors.toList())))
                .thenApply(s -> s.stream()
                        .flatMap(m -> m.entrySet().stream())
                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
    }

    public CompletableFuture<Map<Path, Set<String>>> getAllReadShares(Path start) {
        return getAllDescendantShares(start)
                .thenApply(m -> m.entrySet().stream()
                        .flatMap(e -> e.getValue().readShares().entrySet()
                                .stream()
                                .map(e2 -> new Pair<>(e.getKey().resolve(e2.getKey()), e2.getValue())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right)));
    }

    public CompletableFuture<Map<Path, Set<String>>> getAllWriteShares(Path start) {
        return getAllDescendantShares(start)
                .thenApply(m -> m.entrySet().stream()
                        .flatMap(e -> e.getValue().writeShares().entrySet()
                                .stream()
                                .map(e2 -> new Pair<>(e.getKey().resolve(e2.getKey()), e2.getValue())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right)));
    }

    public CompletableFuture<FileSharedWithState> getSharedWith(Path p) {
        return retrieve(p.getParent())
                .thenApply(opt -> opt.map(s -> s.get(p.toFile().getName())).orElse(FileSharedWithState.EMPTY));
    }

    public CompletableFuture<Boolean> applyAndCommit(Path toFile, Function<SharedWithState, SharedWithState> transform) {
        return retrieveWithFileOrCreate(toFile.getParent()).thenCompose(p -> {
            FileWrapper source = p.left;
            SharedWithState current = p.right;
            SharedWithState updated = transform.apply(current);
            byte[] raw = updated.serialize();
            return source.overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {})
                    .thenApply(x -> true);
        });
    }

    public CompletableFuture<Boolean> addSharedWith(Access access, Path p, Set<String> names) {
        return applyAndCommit(p, current -> current.add(access, p.toFile().getName(), names));
    }

    public CompletableFuture<Boolean> clearSharedWith(Path p) {
        return applyAndCommit(p, current -> current.clear(p.toFile().getName()));
    }

    public CompletableFuture<Boolean> removeSharedWith(Access access, Path p, Set<String> names) {
        return applyAndCommit(p, current -> current.remove(access, p.toFile().getName(), names));
    }
}
