package com.PinkCats.bandwidthoptimizer.debug;

public record ServerBoLogExport(
        String sessionId,
        String fileName,
        byte[] bytes,
        int rangeMinutes,
        int scannedFiles,
        int matchedLines,
        boolean truncated
) {
    public ServerBoLogExport {
        sessionId = sessionId == null ? "" : sessionId;
        fileName = fileName == null ? "server-bo-log.log" : fileName;
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
