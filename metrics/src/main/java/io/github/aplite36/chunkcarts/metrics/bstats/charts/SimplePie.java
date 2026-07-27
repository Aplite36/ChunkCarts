/*
 * Vendored from bStats (https://github.com/Bastian/bstats-metrics), unmodified apart from the
 * package name. Vendored rather than shaded because MetricsBase refuses to run from a package
 * starting with `org.bstats`, and relocating a dependency would mean a shading plugin on all
 * forty-five loader targets.
 *
 * MIT License
 *
 * Copyright (c) 2021 Bastian Oppermann
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.aplite36.chunkcarts.metrics.bstats.charts;

import io.github.aplite36.chunkcarts.metrics.bstats.json.JsonObjectBuilder;

import java.util.concurrent.Callable;

public class SimplePie extends CustomChart {

    private final Callable<String> callable;

    /**
     * Class constructor.
     *
     * @param chartId The id of the chart.
     * @param callable The callable which is used to request the chart data.
     */
    public SimplePie(String chartId, Callable<String> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
        String value = callable.call();
        if (value == null || value.isEmpty()) {
            // Null = skip the chart
            return null;
        }
        return new JsonObjectBuilder()
                .appendField("value", value)
                .build();
    }
}