package com.PinkCats.bandwidthoptimizer.chunk.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ChunkSnapshotFingerprintService {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int SHORT_HASH_HEX_LENGTH = 12;

    private ChunkSnapshotFingerprintService() {}

    public static ChunkSnapshotFingerprint fingerprintOutboundPacket(byte[] encodedPacketBytes) {
        byte[] safeBytes = encodedPacketBytes == null ? new byte[0] : encodedPacketBytes;
        byte[] digestBytes = digest(safeBytes);
        String hashHex = HexFormat.of().formatHex(digestBytes);
        String shortHash = hashHex.length() <= SHORT_HASH_HEX_LENGTH
                ? hashHex
                : hashHex.substring(0, SHORT_HASH_HEX_LENGTH);
        return new ChunkSnapshotFingerprint(
                HASH_ALGORITHM,
                hashHex,
                shortHash,
                safeBytes.length
        );
    }

    private static byte[] digest(byte[] encodedPacketBytes) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM);
            return messageDigest.digest(encodedPacketBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing snapshot hash algorithm: " + HASH_ALGORITHM, exception);
        }
    }
}
