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

import java.util.function.BiConsumer;

public abstract class CustomChart {

    private final String chartId;

    protected CustomChart(String chartId) {
        if (chartId == null) {
            throw new IllegalArgumentException("chartId must not be null");
        }
        this.chartId = chartId;
    }

    public JsonObjectBuilder.JsonObject getRequestJsonObject(BiConsumer<String, Throwable> errorLogger, boolean logErrors) {
        JsonObjectBuilder builder = new JsonObjectBuilder();
        builder.appendField("chartId", chartId);
        try {
            JsonObjectBuilder.JsonObject data = getChartData();
            if (data == null) {
                // If the data is null we don't send the chart.
                return null;
            }
            builder.appendField("data", data);
        } catch (Throwable t) {
            if (logErrors) {

                errorLogger.accept("Failed to get data for custom chart with id " + chartId, t);
            }
            return null;
        }
        return builder.build();
    }

    protected abstract JsonObjectBuilder.JsonObject getChartData() throws Exception;

}
