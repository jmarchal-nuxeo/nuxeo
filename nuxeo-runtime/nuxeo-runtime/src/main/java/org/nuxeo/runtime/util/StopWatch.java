/*
 * (C) Copyright 2006-2017 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 */
package org.nuxeo.runtime.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Usage:
 * <pre>
 * <code>
 * StopWatch sw = new StopWatch()
 * sw.start();
 * ...
 * sw.start("interval-name")
 * sw.stop("interval-name")
 * ..
 * sw.stop()
 * </code>
 * </pre>
 *
 * @author bogdan
 *
 */
public class StopWatch {

    public static class TimeInterval implements Comparable<TimeInterval> {
        public String name;
        public long t0;
        public long t1;
        public TimeInterval(String name) {
            this.name = name;
        }

        /**
         * Elapsed time in nano seconds
         * @return
         */
        public long elapsed() {
            return t1 - t0;
        }

        public long elapsed(TimeUnit unit) {
            return unit.convert(t1 - t0, TimeUnit.NANOSECONDS);
        }

        public void start() {
            this.t0 = System.nanoTime();
        }

        public void stop() {
            this.t1 = System.nanoTime();
        }


        @Override
        public int compareTo(TimeInterval o) {
            long dt = (t1-t0) - (o.t1-o.t0); // this may be out of range for an int
            return dt < 0 ? -1 : (dt > 0 ? 1 : 0);
        }

        public String formatSeconds() {
            return new DecimalFormat("0.000").format(((double)this.t1-this.t0)/1000000000);
        }

        @Override
        public String toString() {
            return name+": "+this.formatSeconds()+" sec.";
        }
    }

    public final TimeInterval total = new TimeInterval("total");
    public final Map<String,TimeInterval> intervals = new HashMap<>();

    public StopWatch start() {
        // reset if needed
        total.t0 = 0;
        total.t1 = 0;
        intervals.clear();

        total.start();
        return this;
    }

    public StopWatch stop() {
        total.stop();
        return this;
    }

    public StopWatch start(String interval) {
        TimeInterval ti = intervals.get(interval);
        if (ti == null) {
            ti = new TimeInterval(interval);
            intervals.put(interval, ti);
        }
        ti.start();
        return this;
    }

    public StopWatch stop(String interval) {
        TimeInterval ti = intervals.get(interval);
        if (ti != null) {
            ti.stop();
        }
        return this;
    }

    public long elapsed(TimeUnit unit) {
        return total.elapsed(unit);
    }

    public long elapsed(String name, TimeUnit unit) {
        TimeInterval ti = intervals.get(name);
        if (ti != null) {
            return total.elapsed(unit);
        }
        return 0;
    }

    public TimeInterval[] sortedIntervals() {
        TimeInterval[] ar = intervals.values().toArray(new TimeInterval[intervals.size()]);
        Arrays.sort(ar);
        return ar;
    }

    public TimeInterval[] rsortedIntervals() {
        TimeInterval[] ar = intervals.values().toArray(new TimeInterval[intervals.size()]);
        Arrays.sort(ar, Collections.reverseOrder());
        return ar;
    }

    public void print(PrintStream ps) {
        for (TimeInterval ti : sortedIntervals()) {
            ps.println(ti.toString());
        }
        ps.println(total);
        ps.flush();
    }

    public void rprint(PrintStream ps) {
        ps.println(total);
        for (TimeInterval ti : rsortedIntervals()) {
            ps.println(ti.toString());
        }
        ps.flush();
    }

    public void print(File file) throws IOException {
        PrintStream ps = new PrintStream(new FileOutputStream(file), false, "UTF-8");
        try {
            for (TimeInterval ti : sortedIntervals()) {
                ps.println(ti.toString());
            }
            ps.println(total);
            ps.flush();
        } finally {
            ps.close();
        }
    }

    public void rprint(File file) throws IOException {
        PrintStream ps = new PrintStream(new FileOutputStream(file), false, "UTF-8");
        try {
            ps.println(total);
            for (TimeInterval ti : rsortedIntervals()) {
                ps.println(ti.toString());
            }
            ps.flush();
        } finally {
            ps.close();
        }
    }

    public String getResults() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        print(ps);
        ps.flush();
        return baos.toString();
    }

    public String getReversedResults() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        rprint(ps);
        ps.flush();
        return baos.toString();
    }

}
