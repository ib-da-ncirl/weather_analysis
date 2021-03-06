/*
 * The MIT License (MIT)
 * Copyright (c) 2020 Ian Buttimer
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package ie.ibuttimer.weather.arima;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import ie.ibuttimer.weather.common.AbstractDriver;
import ie.ibuttimer.weather.common.CKTSMapper;
import ie.ibuttimer.weather.common.CompositeKey;
import ie.ibuttimer.weather.common.TimeSeriesData;
import ie.ibuttimer.weather.hbase.Hbase;
import ie.ibuttimer.weather.hbase.TypeMap;
import ie.ibuttimer.weather.misc.*;
import ie.ibuttimer.weather.transform.DifferencingDriver;
import ie.ibuttimer.weather.transform.TransformDriver;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.mapreduce.Job;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static ie.ibuttimer.weather.Constants.*;
import static ie.ibuttimer.weather.misc.Utils.buildTag;
import static ie.ibuttimer.weather.misc.Utils.heading;

public class ArimaDriver extends AbstractDriver implements IDriver {

    protected ArimaDriver(AppLogger logger) {
        super(logger);
    }

    public static ArimaDriver of(AppLogger logger) {
        return new ArimaDriver(logger);
    }

    private enum Steps { CLEAR, DIFFERENCING, LAG, ARIMA };

    @Override
    public int runJob(Configuration config, JobConfig jobCfg) throws IOException, ClassNotFoundException, InterruptedException {

        Pair<Integer, Map<String, String>> properties =
                getRequiredStringProperties(jobCfg,
                        Lists.newArrayList(CFG_ARIMA_IN_TABLE, CFG_ARIMA_DIFFERENCING_TABLE, CFG_ARIMA_LAGS_TABLE,
                                CFG_ARIMA_OUT_TABLE));

        int resultCode = properties.getKey();

        if (resultCode == STATUS_SUCCESS) {

            Map<String, String> map = properties.getRight();

            String arimaD = jobCfg.getProperty(CFG_ARIMA_D);    // differencing
            boolean zeroTransform = jobCfg.getProperty(CFG_ZERO_TRANSFORM, false);

            List<String> lags = Arrays.asList(jobCfg.getProperty(CFG_ARIMA_P).split(","));

            String stepInTable = map.get(CFG_ARIMA_IN_TABLE);
            String stepOutTable = map.get(CFG_ARIMA_DIFFERENCING_TABLE);
            String lagOutTable = map.get(CFG_ARIMA_LAGS_TABLE);
            String arimaOutTable = map.get(CFG_ARIMA_OUT_TABLE);
            List<String> statsList = Collections.EMPTY_LIST;
            List<String> targetRegexList = Collections.EMPTY_LIST;
            Hbase hbase = null;

            for (Steps step: Steps.values()) {
                if (resultCode != STATUS_SUCCESS) {
                    break;
                }

                switch (step) {
                    case CLEAR:
                        logger.logger().info(heading(
                                String.format("%nStep %d - Clear", step.ordinal() + 1)));

                        try {
                            hbase = deleteTables(jobCfg, Arrays.asList(stepOutTable, lagOutTable, arimaOutTable));
                        } finally {
                            if (hbase != null) {
                                hbase.closeConnection();
                            }
                        }
                        break;

                    case DIFFERENCING:
                        logger.logger().info(heading(
                                String.format("%nStep %d - Differencing", step.ordinal() + 1)));

                        // perform differencing
                        // update config with differencing info for arima
                        jobCfg.setProperty(CFG_DIFFERENCING_IN_TABLE, stepInTable);
                        jobCfg.setProperty(CFG_DIFFERENCING_OUT_TABLE, stepOutTable);
                        jobCfg.setProperty(CFG_DIFFERENCING, STEP+ "," + arimaD);
//                        jobCfg.setProperty(CFG_DIFFERENCING, SEASON+ "," + (24*365));

                        resultCode = DifferencingDriver.of(logger).runJob(config, jobCfg);

                        // Output columns: xxxx_1234_step_0 etc.
                        // for next step
                        stepInTable = stepOutTable;
                        statsList = Arrays.asList(MEAN, VARIANCE);
                        // filter for 'xxxx_1234_step_D' where D is the last differencing step
                        targetRegexList = Arrays.asList(".*", arimaD);
                        break;

                    case LAG:
                        String passThrough = zeroTransform ? "" : " (No processing pass-through)";
                        logger.logger().info(heading(
                                String.format("%nStep %d - Lagging %s", step.ordinal() + 1, passThrough)));

                        if (zeroTransform) {
                            stepOutTable = lagOutTable;

                            // perform lagging
                            Optional<String> diffColumn;
                            try {
                                hbase = hbaseConnection(jobCfg);

                                // add stats
                                HashBasedTable<String, String, Value> stats = loadStats(hbase, jobCfg, stepInTable,
                                        STATS_ROW_MARK_REGEX, statsList);
                                addStatsToConfig(stats, config);    // only req if zero transforming

                                // identify target column
                                diffColumn = idTargetColumn(stats, buildTag(targetRegexList) + "$");
                            } finally {
                                if (hbase != null) {
                                    hbase.closeConnection();
                                }
                            }

                            if (diffColumn.isPresent()) {
                                // update config with lag info for arima
                                jobCfg.setProperty(CFG_COLUMN_LIST, diffColumn.get());
                                jobCfg.setProperty(CFG_KEY_TYPE_MAP, TypeMap.encode(DataTypes.STRING, diffColumn.get()));

                                jobCfg.setProperty(CFG_TRANSFORM_LAG, Integer.toString(lags.size()));
                                jobCfg.setProperty(CFG_TRANSFORM_IN_TABLE, stepInTable);
                                jobCfg.setProperty(CFG_TRANSFORM_OUT_TABLE, stepOutTable);

                                resultCode = TransformDriver.of(logger)
                                        .setAddStats(zeroTransform) // add stats if zero transforming
                                        .runJob(config, jobCfg);
                            } else {
                                resultCode = STATUS_FAIL;
                                logger.error("Unable to identify target column for lagging");
                            }

                            // for next step
                            // Output columns: xxxx_1234_step_1_lag_1 etc.
                            statsList = Arrays.asList(AUTOCOVARIANCE, AUTOCORRELATION);
                            // filter for 'xxxx_1234_step_D_lag_P' where D is the last differencing step & P is the last lag
                            // (in theory but just need column 0)
                            targetRegexList = Arrays.asList(".*", arimaD, LAG, "0");
                        }

                        // for next step
                        stepInTable = stepOutTable;
                        break;

                    case ARIMA:
                        logger.logger().info(heading(
                                String.format("%nStep %d - ARIMA", step.ordinal() + 1)));
                        stepOutTable = arimaOutTable;

                        // perform arima
                        Optional<String> arimaColumn;

                        // create output table if necessary
                        try {
                            hbase = createTable(jobCfg, stepOutTable);

                            // load stats
                            HashBasedTable<String, String, Value> stats = loadStats(hbase, jobCfg, stepInTable,
                                    STATS_ROW_MARK_REGEX, statsList);

                            // identify target column
                            arimaColumn = idTargetColumn(stats, buildTag(targetRegexList)+"$");
                        } finally {
                            if (hbase != null) {
                                hbase.closeConnection();
                            }
                        }

                        if (arimaColumn.isPresent()) {
                            // update config with lag info for arima
                            jobCfg.setProperty(CFG_COLUMN_LIST, arimaColumn.get());
                            jobCfg.setProperty(CFG_KEY_TYPE_MAP, TypeMap.encode(DataTypes.STRING, arimaColumn.get()));

                            Job job = initJob(config, jobCfg, "ARIMA");

                            TableMapReduceUtil.initTableMapperJob(
                                    stepInTable,           // input table
                                    initScan(jobCfg),     // Scan instance to control CF and attribute selection
                                    CKTSMapper.class,     // mapper class
                                    CompositeKey.class,   // mapper output key
                                    TimeSeriesData.class, // mapper output value
                                    job);

                            TableMapReduceUtil.initTableReducerJob(
                                    stepOutTable,   // output table
                                    ArimaTableReducer.class,   // reducer class
                                    job);

                            resultCode = startJob(job, jobCfg);
                        } else {
                            resultCode = STATUS_FAIL;
                            logger.error("Unable to identify target column for arima");
                        }
                        break;
                }
            }

            if (resultCode == STATUS_SUCCESS) {
                saveResults(jobCfg, stepOutTable, jobCfg.getProperty(CFG_ARIMA_PATH_ROOT, ""), logger);
            }
        }
        return resultCode;
    }

    private Optional<String> idTargetColumn(HashBasedTable<String, String, Value> stats, String regex) {
        AtomicReference<Optional<String>> name = new AtomicReference<>(Optional.empty());
        // identify target column
        stats.rowKeySet().stream()
            .filter(r -> r.matches(regex))
            .findFirst()
            .ifPresent(r -> name.set(Optional.of(r.substring(STATS_ROW_MARK.length()))));
        return name.get();
    }

    public static void saveResults(JobConfig jobCfg, String table, String saveTo, AppLogger logger) throws IOException {

        saveDriverResults(jobCfg, table, Arrays.asList(MSE, MAAPE, AIC_MSE, AIC_MAAPE, BIC_MSE, BIC_MAAPE, PARAMS), saveTo, logger);
    }
}
