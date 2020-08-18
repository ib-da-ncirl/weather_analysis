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

package ie.ibuttimer.weather.analysis;

import com.google.common.collect.Lists;
import ie.ibuttimer.weather.Constants;
import ie.ibuttimer.weather.common.*;
import ie.ibuttimer.weather.hbase.Hbase;
import ie.ibuttimer.weather.misc.AppLogger;
import ie.ibuttimer.weather.misc.IDriver;
import ie.ibuttimer.weather.misc.JobConfig;
import ie.ibuttimer.weather.sma.SmaPartitioner;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.mapreduce.Job;

import java.io.IOException;
import java.util.Map;

import static ie.ibuttimer.weather.Constants.*;

public class AnalysisDriver extends AbstractDriver implements IDriver {

    protected AnalysisDriver(AppLogger logger) {
        super(logger);
    }

    public static AnalysisDriver of(AppLogger logger) {
        return new AnalysisDriver(logger);
    }

    @Override
    public int runJob(Configuration config, JobConfig jobCfg) throws IOException, ClassNotFoundException, InterruptedException {

        Pair<Integer, Map<String, String>> properties =
                getRequiredStringProperies(jobCfg, Lists.newArrayList(CFG_WEATHER_TABLE, CFG_ANALYSIS_TABLE));

        int resultCode = properties.getKey();

        if (resultCode == STATUS_SUCCESS) {

            Map<String, String> map = properties.getRight();

            Job job = initJob(config, jobCfg, "Analysis");

            TableMapReduceUtil.initTableMapperJob(
                    map.get(CFG_WEATHER_TABLE), // input table
                    initScan(jobCfg),     // Scan instance to control CF and attribute selection
                    CKTSMapper.class,     // mapper class
                    CompositeKey.class,   // mapper output key
                    TimeSeriesData.class, // mapper output value
                    job);

            // create output table if necessary
            TableName analysisTable = TableName.valueOf(map.get(CFG_ANALYSIS_TABLE));
            Hbase hbase = null;
            try {
                hbase = Hbase.of(jobCfg.getProperty(CFG_HBASE_RESOURCE, DFLT_HBASE_RESOURCE));
                if (!hbase.tableExists(analysisTable)) {
                    hbase.createTable(analysisTable.getNameAsString(), FAMILY);
                }
            } finally {
                if (hbase != null) {
                    hbase.closeConnection();
                }
            }

            TableMapReduceUtil.initTableReducerJob(
                    analysisTable.getNameAsString(),   // output table
                    AnalysisTableReducer.class,   // reducer class
                    job);

            job.setPartitionerClass(SmaPartitioner.class);
            job.setGroupingComparatorClass(CompositeKeyGrouping.class); // comparator that controls which keys are grouped together for a single call to Reducer
            job.setSortComparatorClass(CompositeKeyComparator.class);   // comparator that controls how the keys are sorted before they are passed to the Reducer

            if (jobCfg.isWait()) {
                resultCode = job.waitForCompletion(jobCfg.isVerbose()) ? STATUS_SUCCESS : STATUS_FAIL;
            } else {
                job.submit();
                resultCode = STATUS_RUNNING;
            }
        }
        return resultCode;
    }

}