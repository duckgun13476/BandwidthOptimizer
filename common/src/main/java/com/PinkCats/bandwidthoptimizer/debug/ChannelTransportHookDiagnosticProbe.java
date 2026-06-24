package com.PinkCats.bandwidthoptimizer.debug;

public final class ChannelTransportHookDiagnosticProbe {

    private ChannelTransportHookDiagnosticProbe() {}

    public static boolean BO_Diag_chunkTransportFrames() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_TRANSPORT_FRAMES);
    }

    public static boolean BO_Diag_transportTraceJournal() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.TRANSPORT_TRACE_JOURNAL);
    }
}
