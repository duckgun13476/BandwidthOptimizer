package com.PinkCats.bandwidthoptimizer.recipe;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class RecipeSyncPersistentStore {

    private static final int MAGIC = 0x424F5242;
    private static final int VERSION = 1;
    private static final int MAX_SERVER_BASES_PER_SCOPE = 16;
    private static final int MAX_CLIENT_BASES_PER_SCOPE = 5;
    private static final String ROOT_DIRECTORY = "recipe-sync-cache-v1";
    private static final String RECENT_FILE = "recent.txt";
    private static final String ALIASES_FILE = "semantic-aliases.txt";
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BandwidthOptimizer-RecipeCache");
        thread.setDaemon(true);
        return thread;
    });

    private RecipeSyncPersistentStore() {}

    public static CompletableFuture<StoredBase> loadLatestClientAsync(String scopeHash) {
        return CompletableFuture.supplyAsync(() -> {
            Path latest = roleScopeDirectory(Role.CLIENT, scopeHash).resolve("latest.txt");
            try {
                if (!Files.isRegularFile(latest)) {
                    return null;
                }
                String hash = Files.readString(latest, StandardCharsets.US_ASCII).trim();
                return load(Role.CLIENT, scopeHash, hash);
            } catch (IOException exception) {
                warn("read latest client recipe base", scopeHash, exception);
                return null;
            }
        }, IO_EXECUTOR);
    }

    public static CompletableFuture<List<StoredBase>> loadRecentClientAsync(String scopeHash) {
        return CompletableFuture.supplyAsync(
                () -> loadRecent(Role.CLIENT, scopeHash, MAX_CLIENT_BASES_PER_SCOPE), IO_EXECUTOR);
    }

    public static CompletableFuture<StoredBase> loadServerAsync(String scopeHash, String recipeHash) {
        return CompletableFuture.supplyAsync(() -> load(Role.SERVER, scopeHash, recipeHash), IO_EXECUTOR);
    }

    public static CompletableFuture<Boolean> storeClientAsync(String scopeHash, byte[] packetBytes) {
        return storeClientAsync(scopeHash, packetBytes == null ? "" : RecipeSyncDeltaCodec.sha256(packetBytes), packetBytes);
    }

    public static CompletableFuture<Boolean> storeClientAsync(
            String scopeHash, String semanticHash, byte[] packetBytes) {
        return storeAsync(Role.CLIENT, scopeHash, semanticHash, packetBytes, MAX_CLIENT_BASES_PER_SCOPE);
    }

    public static CompletableFuture<Boolean> storeServerAsync(String scopeHash, byte[] packetBytes) {
        return storeServerAsync(scopeHash, packetBytes == null ? "" : RecipeSyncDeltaCodec.sha256(packetBytes), packetBytes);
    }

    public static CompletableFuture<Boolean> storeServerAsync(
            String scopeHash, String semanticHash, byte[] packetBytes) {
        return storeAsync(Role.SERVER, scopeHash, semanticHash, packetBytes, MAX_SERVER_BASES_PER_SCOPE);
    }

    static StoredBase load(Role role, String scopeHash, String recipeHash) {
        if (!isSafeScope(scopeHash) || !RecipeSyncDeltaCodec.isHash(recipeHash)) {
            return null;
        }
        Path blob = blobPath(role, scopeHash, recipeHash);
        if (!Files.isRegularFile(blob)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(
                Files.newInputStream(blob))))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported recipe cache blob");
            }
            int length = input.readInt();
            if (length <= 0 || length > ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES) {
                throw new IOException("Recipe cache blob length is out of bounds");
            }
            String storedHash = input.readUTF();
            byte[] packetBytes = input.readNBytes(length);
            if (packetBytes.length != length || input.read() != -1
                    || !recipeHash.equals(storedHash)
                    || !recipeHash.equals(RecipeSyncDeltaCodec.sha256(packetBytes))) {
                throw new IOException("Recipe cache blob failed integrity validation");
            }
            String semanticHash = semanticForRaw(roleScopeDirectory(role, scopeHash), recipeHash);
            return new StoredBase(recipeHash, semanticHash, packetBytes);
        } catch (IOException exception) {
            deleteCorrupt(blob, roleScopeDirectory(role, scopeHash).resolve("latest.txt"), recipeHash);
            warn("read recipe cache blob", scopeHash, exception);
            return null;
        }
    }

    private static List<StoredBase> loadRecent(Role role, String scopeHash, int limit) {
        if (!isSafeScope(scopeHash) || limit <= 0) {
            return List.of();
        }
        Path directory = roleScopeDirectory(role, scopeHash);
        List<String> hashes = loadRecentHashes(directory, limit);
        ArrayList<StoredBase> bases = new ArrayList<>();
        for (String hash : hashes) {
            StoredBase base = load(role, scopeHash, hash);
            if (base != null) {
                bases.add(base);
            }
        }
        return List.copyOf(bases);
    }

    private static List<String> loadRecentHashes(Path directory, int limit) {
        ArrayList<String> hashes = new ArrayList<>(limit);
        try {
            Path latest = directory.resolve("latest.txt");
            if (Files.isRegularFile(latest)) {
                addHash(hashes, Files.readString(latest, StandardCharsets.US_ASCII).trim(), limit);
            }
            Path recent = directory.resolve(RECENT_FILE);
            if (Files.isRegularFile(recent) && Files.size(recent) <= 4096L) {
                for (String hash : Files.readAllLines(recent, StandardCharsets.US_ASCII)) {
                    addHash(hashes, hash.trim(), limit);
                }
            }
            Path blobs = directory.resolve("blobs");
            if (Files.isDirectory(blobs)) {
                List<Path> files;
                try (var stream = Files.list(blobs)) {
                    files = stream.filter(Files::isRegularFile)
                            .sorted(Comparator.comparingLong(RecipeSyncPersistentStore::lastModified).reversed())
                            .toList();
                }
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".bor")) {
                        continue;
                    }
                    String hash = name.substring(0, name.length() - 4);
                    addHash(hashes, hash, limit);
                }
            }
        } catch (IOException exception) {
            warn("list recent recipe cache bases", directory.getFileName().toString(), exception);
        }
        return List.copyOf(hashes);
    }

    private static CompletableFuture<Boolean> storeAsync(
            Role role, String scopeHash, String semanticHash, byte[] packetBytes, int retain) {
        byte[] safeBytes = packetBytes == null ? null : packetBytes.clone();
        return CompletableFuture.supplyAsync(() -> {
            if (!isSafeScope(scopeHash) || !RecipeSyncDeltaCodec.isHash(semanticHash)
                    || safeBytes == null || safeBytes.length == 0
                    || safeBytes.length > ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES) {
                return false;
            }
            String hash = RecipeSyncDeltaCodec.sha256(safeBytes);
            Path directory = roleScopeDirectory(role, scopeHash);
            Path blob = blobPath(role, scopeHash, hash);
            try {
                Files.createDirectories(directory.resolve("blobs"));
                if (!Files.isRegularFile(blob) || load(role, scopeHash, hash) == null) {
                    Path temporary = blob.resolveSibling(blob.getFileName() + ".tmp");
                    try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                            Files.newOutputStream(temporary))))) {
                        output.writeInt(MAGIC);
                        output.writeInt(VERSION);
                        output.writeInt(safeBytes.length);
                        output.writeUTF(hash);
                        output.write(safeBytes);
                    }
                    atomicMove(temporary, blob);
                }
                ArrayList<String> recentHashes = new ArrayList<>(retain);
                addHash(recentHashes, hash, retain);
                for (String recentHash : loadRecentHashes(directory, retain)) {
                    addHash(recentHashes, recentHash, retain);
                }
                writeLatest(directory.resolve("latest.txt"), hash);
                writeRecent(directory.resolve(RECENT_FILE), recentHashes);
                LinkedHashMap<String, String> aliases = new LinkedHashMap<>(loadAliases(directory));
                aliases.put(semanticHash, hash);
                aliases.entrySet().removeIf(entry -> !recentHashes.contains(entry.getValue()));
                writeAliases(directory.resolve(ALIASES_FILE), aliases);
                prune(directory.resolve("blobs"), recentHashes);
                return true;
            } catch (IOException exception) {
                warn("write recipe cache blob", scopeHash, exception);
                return false;
            }
        }, IO_EXECUTOR);
    }

    private static void writeLatest(Path latest, String hash) throws IOException {
        Path temporary = latest.resolveSibling(latest.getFileName() + ".tmp");
        Files.writeString(temporary, hash + System.lineSeparator(), StandardCharsets.US_ASCII);
        atomicMove(temporary, latest);
    }

    private static void writeRecent(Path recent, List<String> hashes) throws IOException {
        Path temporary = recent.resolveSibling(recent.getFileName() + ".tmp");
        Files.write(temporary, hashes, StandardCharsets.US_ASCII);
        atomicMove(temporary, recent);
    }

    private static void writeAliases(Path aliasesFile, Map<String, String> aliases) throws IOException {
        ArrayList<String> lines = new ArrayList<>(aliases.size());
        aliases.forEach((semanticHash, rawHash) -> lines.add(semanticHash + "=" + rawHash));
        Path temporary = aliasesFile.resolveSibling(aliasesFile.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.US_ASCII);
        atomicMove(temporary, aliasesFile);
    }

    private static Map<String, String> loadAliases(Path directory) {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        Path aliasesFile = directory.resolve(ALIASES_FILE);
        try {
            if (!Files.isRegularFile(aliasesFile) || Files.size(aliasesFile) > 8192L) {
                return aliases;
            }
            for (String line : Files.readAllLines(aliasesFile, StandardCharsets.US_ASCII)) {
                int separator = line.indexOf('=');
                if (separator <= 0 || separator == line.length() - 1) {
                    continue;
                }
                String semanticHash = line.substring(0, separator);
                String rawHash = line.substring(separator + 1);
                if (RecipeSyncDeltaCodec.isHash(semanticHash) && RecipeSyncDeltaCodec.isHash(rawHash)) {
                    aliases.put(semanticHash, rawHash);
                }
            }
        } catch (IOException exception) {
            warn("read recipe semantic aliases", directory.getFileName().toString(), exception);
        }
        return aliases;
    }

    private static String semanticForRaw(Path directory, String rawHash) {
        for (Map.Entry<String, String> entry : loadAliases(directory).entrySet()) {
            if (rawHash.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return rawHash;
    }

    private static void prune(Path blobs, List<String> retainedHashes) throws IOException {
        if (!Files.isDirectory(blobs)) {
            return;
        }
        try (var stream = Files.list(blobs)) {
            for (Path candidate : stream.filter(Files::isRegularFile).toList()) {
                String name = candidate.getFileName().toString();
                if (!name.endsWith(".bor")) {
                    continue;
                }
                String hash = name.substring(0, name.length() - 4);
                if (!retainedHashes.contains(hash)) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    private static void addHash(List<String> hashes, String hash, int limit) {
        if (hashes.size() < limit && RecipeSyncDeltaCodec.isHash(hash) && !hashes.contains(hash)) {
            hashes.add(hash);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static void deleteCorrupt(Path blob, Path latest, String hash) {
        try {
            Files.deleteIfExists(blob);
            if (Files.isRegularFile(latest)
                    && hash.equals(Files.readString(latest, StandardCharsets.US_ASCII).trim())) {
                Files.deleteIfExists(latest);
            }
        } catch (IOException ignored) {
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path roleScopeDirectory(Role role, String scopeHash) {
        String safeScope = isSafeScope(scopeHash) ? scopeHash : "invalid";
        return BandwidthOptimizerOutputPaths.outputRoot()
                .resolve(ROOT_DIRECTORY)
                .resolve(role.directory())
                .resolve(safeScope);
    }

    private static Path blobPath(Role role, String scopeHash, String hash) {
        String safeHash = RecipeSyncDeltaCodec.isHash(hash) ? hash : "invalid";
        return roleScopeDirectory(role, scopeHash).resolve("blobs").resolve(safeHash + ".bor");
    }

    private static boolean isSafeScope(String scopeHash) {
        return RecipeSyncDeltaCodec.isHash(scopeHash);
    }

    private static void warn(String operation, String scopeHash, Exception exception) {
        Bandwidthoptimizer.LOGGER.warn("[RecipeCache] Failed to {} for scope={}", operation,
                scopeHash == null ? "<null>" : scopeHash, exception);
    }

    public enum Role {
        CLIENT("client"),
        SERVER("server");

        private final String directory;

        Role(String directory) {
            this.directory = directory;
        }

        String directory() {
            return this.directory;
        }
    }

    public record StoredBase(String hash, String semanticHash, byte[] packetBytes) {
        public StoredBase {
            semanticHash = RecipeSyncDeltaCodec.isHash(semanticHash) ? semanticHash : hash;
            packetBytes = packetBytes == null ? new byte[0] : packetBytes.clone();
        }

        public byte[] copyPacketBytes() {
            return this.packetBytes.clone();
        }

        @Override
        public byte[] packetBytes() {
            return copyPacketBytes();
        }
    }
}
