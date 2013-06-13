/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.lealone.hbase.result;

import java.util.List;

import com.codefollower.lealone.result.DelegatedResult;
import com.codefollower.lealone.result.ResultInterface;
import com.codefollower.lealone.result.SortOrder;
import com.codefollower.lealone.value.Value;

public class HBaseSortedResult extends DelegatedResult {
    private static Value[] END = new Value[0];
    private final SortOrder sort;
    private final ResultInterface[] results;
    private final int size;
    private int rowCount = -1;
    private Value[] currentRow;
    private Value[][] currentRows;

    public HBaseSortedResult(SortOrder sort, List<ResultInterface> results) {
        this.sort = sort;
        this.results = results.toArray(new ResultInterface[results.size()]);
        this.result = this.results[0];
        this.size = this.results.length;
        currentRows = new Value[size][];
    }

    @Override
    public void reset() {
        for (int i = 0; i < size; i++)
            results[i].reset();
    }

    @Override
    public Value[] currentRow() {
        return currentRow;
    }

    @Override
    public boolean next() {
        int next = -1;
        Value[] row = null;
        for (int i = 0; i < size; i++) {
            if (currentRows[i] == END)
                continue;
            if (currentRows[i] == null) {
                if (results[i].next())
                    currentRows[i] = results[i].currentRow();
                else
                    currentRows[i] = END;
            }
            if (currentRows[i] != END) {
                if (next == -1) {
                    next = i;
                    row = currentRows[i];
                } else {
                    if (sort.compare(row, currentRows[i]) > 0) {
                        next = i;
                        row = currentRows[i];
                    }
                }
            }
        }
        currentRow = row;
        currentRows[next] = null;
        return currentRow != null;
    }

    @Override
    public boolean needToClose() {
        boolean needToClose = true;
        for (int i = 0; i < size; i++)
            needToClose = needToClose && results[i].needToClose();

        return needToClose;
    }

    @Override
    public void close() {
        for (int i = 0; i < size; i++)
            results[i].close();
    }

    @Override
    public int getRowCount() {
        int c = 0;
        if (rowCount == -2)
            return -1;
        if (rowCount == -1) {
            for (int i = 0; i < size; i++) {
                if (results[i].getRowCount() == -1) {
                    rowCount = -2;
                    return -1;
                } else {
                    c += results[i].getRowCount();
                }
            }
        }
        rowCount = c;
        return c;
    }
}
