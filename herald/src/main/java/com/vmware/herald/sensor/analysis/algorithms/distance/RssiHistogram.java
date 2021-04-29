package com.vmware.herald.sensor.analysis.algorithms.distance;

import com.vmware.herald.sensor.DefaultSensorDelegate;
import com.vmware.herald.sensor.data.TextFile;
import com.vmware.herald.sensor.datatype.Date;
import com.vmware.herald.sensor.datatype.Proximity;
import com.vmware.herald.sensor.datatype.ProximityMeasurementUnit;
import com.vmware.herald.sensor.datatype.SensorType;
import com.vmware.herald.sensor.datatype.TargetIdentifier;
import com.vmware.herald.sensor.datatype.TimeInterval;

/// Accumulate histogram of all RSSI measurements to build
/// a profile of the receiver for normalisation
public class RssiHistogram extends DefaultSensorDelegate {
    public final int min, max;
    public final long[] histogram;
    private final long[] cdf;
    private final double[] transform;
    private final TextFile textFile;
    private final TimeInterval updatePeriod;
    private Date lastUpdateTime = new Date(0);
    private long samples = 0;

    /// Accumulate histogram of RSSI for value range [min, max] and auto-write profile to storage at regular intervals
    public RssiHistogram(final int min, final int max, final TimeInterval updatePeriod, final TextFile textFile) {
        this.min = min;
        this.max = max;
        this.histogram = new long[max - min + 1];
        this.cdf = new long[histogram.length];
        this.transform = new double[histogram.length];
        this.textFile = textFile;
        this.updatePeriod = updatePeriod;
        clear();
        if (textFile != null) {
            read(textFile);
            update();
        }
    }

    /// Accumulate a RSSI profile for value range [min, max] in-memory only
    public RssiHistogram(final int min, final int max) {
        this(min, max, TimeInterval.minute, null);
    }

    @Override
    public void sensor(SensorType sensor, Proximity didMeasure, TargetIdentifier fromTarget) {
        // Guard for data collection until histogram reaches maximum count
        if (samples == Long.MAX_VALUE) {
            return;
        }
        // Guard for RSSI measurements only
        if (didMeasure.unit != ProximityMeasurementUnit.RSSI || didMeasure.value == null) {
            return;
        }
        add(didMeasure.value);
    }

    /// Add RSSI sample
    public synchronized void add(final double rssiValue) {
        // Guard for RSSI range
        final int rssi = (int) Math.round(rssiValue);
        if (rssi < min || rssi > max) {
            return;
        }
        // Increment count
        final int index = rssi - min;
        histogram[index]++;
        samples++;
        // Update at regular intervals
        final Date time = new Date();
        if (lastUpdateTime.secondsSinceUnixEpoch() + updatePeriod.value < time.secondsSinceUnixEpoch()) {
            if (textFile != null) {
                write(textFile);
            }
            update();
            lastUpdateTime = time;
        }
    }

    public void clear() {
        for (int i=histogram.length; i-->0;) {
            histogram[i] = 0;
            cdf[i] = 0;
        }
        for (int i=transform.length; i-->0;) {
            transform[i] = i;
        }
        samples = 0;
    }

    public int samplePercentile(double percentile) {
        if (samples == 0) {
            return (int) Math.round(min + (max - min) * percentile);
        }
        final double percentileCount = samples * percentile;
        for (int i=0; i<cdf.length; i++) {
            if (cdf[i] >= percentileCount) {
                return min + i;
            }
        }
        return max;
    }

    public double normalisedPercentile(double percentile) {
        return normalise(samplePercentile(percentile));
    }

    /// Read profile data from storage, this replaces existing in-memory profile
    public void read(TextFile textFile) {
        clear();
        final String content = textFile.contentsOf();
        for (final String row : content.split("\n")) {
            final String[] cols = row.split(",", 2);
            if (cols.length != 2) {
                continue;
            }
            final int rssi = Integer.parseInt(cols[0]);
            final long count = Long.parseLong(cols[1]);
            if (rssi < min || rssi > max) {
                continue;
            }
            final int index = rssi - min;
            histogram[index] = count;
            samples += count;
        }
    }

    /// Render profile data as CSV (RSSI,count)
    private String toCsv() {
        final StringBuilder s = new StringBuilder();
        for (int i=0; i<histogram.length; i++) {
            final int rssi = min + i;
            final String row = rssi + "," +histogram[i] + "\n";
            s.append(row);
        }
        return s.toString();
    }

    /// Write profile data to storage
    public void write(TextFile textFile) {
        final String content = toCsv();
        textFile.overwrite(content);
    }

    // MARK: - Histogram equalisation

    /// Compute cumulative distribution function (CDF) of histogram
    private static void cumulativeDistributionFunction(final long[] histogram, final long[] cdf) {
        long sum = 0;
        for (int i = 0; i < histogram.length; i++) {
            cdf[i] = (sum += histogram[i]);
        }
    }

//    /// Compute transformation table for equalising histogram to target CDF.
//    /// Use this to normalise all phones towards a common histogram profile.
//    private static void equalisation(final long[] histogram, final long[] cdf, final double[] transform) {
//        final double sum = cdf[cdf.length - 1];
//        final long max = cdf.length - 1;
//        if (sum > 0) {
//            int j = 0;
//            for (int i = 0; i < histogram.length; i++) {
//                for (; j < cdf.length; j++) {
//                    if (histogram[i] <= cdf[j]) {
//                        transform[i] = j;
//                        break;
//                    }
//                }
//            }
//        }
//    }

    /// Compute transformation table for normalising histogram to maximise its dynamic range
    private static void normalisation(final long[] cdf, final double[] transform) {
        final double sum = cdf[cdf.length - 1];
        final long max = cdf.length - 1;
        if (sum > 0) {
            for (int i = cdf.length; i-- > 0; ) {
                transform[i] = max * cdf[i] / sum;
            }
        }
    }

    public void update() {
        cumulativeDistributionFunction(histogram, cdf);
        normalisation(cdf, transform);
    }

    // MARK: - Normalisation

    public double normalise(final double rssi) {
        final int index = (int) (Math.round(rssi) - min);
        if (index < 0) {
            return min + transform[0];
        }
        if (index >= transform.length) {
            return min + transform[transform.length - 1];
        }
        return min + transform[index];
    }
//
//    public Proximity normalise(Proximity proximity) {
//        if (proximity.unit != ProximityMeasurementUnit.RSSI) {
//            return proximity;
//        }
//        final Proximity normalised = new Proximity(proximity.unit, normalise(proximity.value), proximity.calibration);
//        return normalised;
//    }

    @Override
    public String toString() {
        return "RssiHistogram{samples="+samples+",p05="+samplePercentile(0.05)+",p50="+samplePercentile(0.5) + ",p95="+samplePercentile(0.95)+"}";
    }
}
