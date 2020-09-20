/*
 * Copyright Chris2018998
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.beecp.pool;

/**
 * PreparedStatement Cache Key
 *
 * @author Chris.Liao
 * @version 1.0
 */
final class PreparedStatementKey {
    private int h;
    private Object[] v;

    public PreparedStatementKey(int type, String sql) {
        v = new Object[]{type, sql};
        h = hashCode(v);
    }

    public PreparedStatementKey(int type, String sql, int autoGeneratedKeys) {
        v = new Object[]{type, sql, autoGeneratedKeys};
        h = hashCode(v);
    }

    public PreparedStatementKey(int type, String sql, int[] columnIndexes) {
        v = new Object[]{type, sql, columnIndexes};
        h = hashCode(v);
    }

    public PreparedStatementKey(int type, String sql, String[] columnNames) {
        v = new Object[]{type, sql, columnNames};
        h = hashCode(v);
    }

    public PreparedStatementKey(int type, String sql, int resultSetType, int resultSetConcurrency) {
        v = new Object[]{type, sql, resultSetType, resultSetConcurrency};
        h = hashCode(v);
    }

    public PreparedStatementKey(int type, String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
        v = new Object[]{type, sql, resultSetType, resultSetConcurrency, resultSetHoldability};
        h = hashCode(v);
    }

    private static final int hashCode(Object a[]) {
        int h = 1;
        for (Object e : a)
            h = 31 * h + e.hashCode();
        return h;
    }

    public int hashCode() {
        return h;
    }

    public boolean equals(Object obj) {
        Object[] ov = ((PreparedStatementKey) obj).v;
        for (int i = 0, l = v.length; i < l; i++) {
            if (!v[i].equals(ov[i]))
                return false;
        }
        return true;
    }
}