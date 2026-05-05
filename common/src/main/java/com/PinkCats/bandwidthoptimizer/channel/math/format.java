package com.PinkCats.bandwidthoptimizer.channel.math;

public class format {


    // Change byte to text
    public static String ratioText(long currentBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(java.util.Locale.ROOT, "%.3fx", (double) currentBytes / (double) baselineBytes);
    }


}
