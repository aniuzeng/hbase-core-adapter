package com.lm.hbase.adapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.io.compress.Compression.Algorithm;
import org.apache.hadoop.hbase.util.Bytes;

import com.lm.hbase.adapter.ColumnFamilyParam.ColumnFamilyFieldEnum;
import com.lm.hbase.adapter.entity.HBasePageModel;
import com.lm.hbase.adapter.entity.HbaseQualifier;
import com.lm.hbase.adapter.entity.QualifierValue;

public class HbaseUtil {

    private static Connection connection = null;

    public static void init(String zkPort, String zkQuorum, String hbaseMaster, String znodeParent) throws IOException {
        System.out.println(zkQuorum);
        System.out.println("初始化Hbase链接...");
        Configuration configuration = HBaseConfiguration.create();
        configuration.set("hbase.zookeeper.property.clientPort", zkPort);
        configuration.set("hbase.zookeeper.quorum", zkQuorum);
        configuration.set("hbase.master", hbaseMaster);
        configuration.set("zookeeper.znode.parent", znodeParent);
        configuration.setInt("hbase.rpc.timeout", 50000);
        configuration.setInt("hbase.client.operation.timeout", 10000);
        configuration.setInt("hbase.client.scanner.timeout.period", 200000);
        connection = ConnectionFactory.createConnection(configuration);
        System.out.println("Hbase初始化成功");

    }

    public static Connection getConn() throws Exception {
        if (connection == null) {
            throw new Exception("HbaseUtil is not initialized");
        }
        return connection;
    }

    public static void close() throws IOException {
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * 创建表。提供更加高级的功能创建hbase表。
     * 
     * @param tableName 表名
     * @param columnFamilys 列族
     */
    public static void createTable(String tableName, byte[][] splitKeys, byte[] startKey, byte[] endKey, int numRegions,
                                   ColumnFamilyParam... columnFamilys) throws Exception {
        Admin hBaseAdmin = null;
        try {
            Connection connection = getConn();
            hBaseAdmin = connection.getAdmin();
            TableName hbaseTableName = TableName.valueOf(tableName);

            if (hBaseAdmin.tableExists(hbaseTableName)) {
                throw new Exception(tableName + " is exist");
            }
            HTableDescriptor tableDescriptor = new HTableDescriptor(hbaseTableName);
            for (ColumnFamilyParam item : columnFamilys) {

                Object familyName = item.get(ColumnFamilyFieldEnum.COLUMN_FAMILY_NAME);
                if (familyName == null) {
                    throw new Exception("COLUMN_FAMILY_NAME is null");
                }
                HColumnDescriptor columnDescriptor = new HColumnDescriptor(familyName.toString());
                columnDescriptor.setCompressionType(Algorithm.SNAPPY);

                Object timeToLive = item.get(ColumnFamilyFieldEnum.TIME_TO_LIVE);
                if (timeToLive != null) {
                    columnDescriptor.setTimeToLive(Integer.parseInt(timeToLive.toString()));
                }

                Object maxVersion = item.get(ColumnFamilyFieldEnum.MAX_VERSION);
                if (maxVersion != null) {
                    columnDescriptor.setMaxVersions(Integer.parseInt(maxVersion.toString()));
                }

                tableDescriptor.addFamily(columnDescriptor);

            }
            if (splitKeys != null) {
                hBaseAdmin.createTable(tableDescriptor, splitKeys);
            } else if (startKey != null && endKey != null && numRegions > 0) {
                hBaseAdmin.createTable(tableDescriptor, startKey, endKey, numRegions);
            } else {
                hBaseAdmin.createTable(tableDescriptor);
            }
        } finally {
            if (hBaseAdmin != null) {
                try {
                    hBaseAdmin.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 创建表
     * 
     * @param tableName 表名
     * @param columnFamilys 列族
     */
    public static void createTable(String tableName, String... columnFamilys) throws Exception {
        Admin hBaseAdmin = null;
        try {
            Connection connection = getConn();
            hBaseAdmin = connection.getAdmin();
            TableName hbaseTableName = TableName.valueOf(tableName);

            if (hBaseAdmin.tableExists(hbaseTableName)) {
                throw new Exception(tableName + " is exist");
            }
            HTableDescriptor tableDescriptor = new HTableDescriptor(hbaseTableName);
            for (String columnFamily : columnFamilys) {
                tableDescriptor.addFamily(new HColumnDescriptor(columnFamily));
            }
            hBaseAdmin.createTable(tableDescriptor);
        } finally {
            if (hBaseAdmin != null) {
                try {
                    hBaseAdmin.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 插入数据
     * 
     * @param tableName 表名
     * @param columns 请仔细查看ColumnFamily对象的用法
     */
    public static void insertData(TableName tableName, String rowKey, ColumnFamily... columns) throws Exception {
        Table table = null;
        try {
            Connection connection = getConn();
            table = connection.getTable(tableName);
            Put put = new Put(rowKey.getBytes());
            for (ColumnFamily columnFamily : columns) {
                while (columnFamily.hasNext() != -1) {
                    Entry<String, QualifierColumn> entry = columnFamily.next();
                    put.addColumn(columnFamily.getFamilyName().getBytes(), entry.getValue().getQualifier(),
                                  entry.getValue().getV());
                }

            }
            table.put(put);
        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 批量插入数据
     * 
     * @param tableName
     * @param rowList
     */
    public static void batchInsertData(TableName tableName, List<Row> rowList) throws Exception {
        Table table = null;
        try {
            Connection connection = getConn();
            table = connection.getTable(tableName);

            List<org.apache.hadoop.hbase.client.Row> puts = new ArrayList<>();
            for (Row row : rowList) {// 行
                Put put = new Put(row.getRowKey().getBytes());
                Iterator<Map.Entry<byte[], ColumnFamily>> iterator = row.getColumnFamilys().entrySet().iterator();
                while (iterator.hasNext()) {// 列族
                    ColumnFamily columnFamily = iterator.next().getValue();
                    while (columnFamily.hasNext() != -1) {// 列
                        Entry<String, QualifierColumn> entry = columnFamily.next();
                        put.addColumn(columnFamily.getFamilyName().getBytes(), entry.getValue().getQualifier(),
                                      entry.getValue().getV());
                    }

                }
                puts.add(put);
            }
            Object[] results = new Object[puts.size()];
            table.batch(puts, results);

        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String getDisplayValue(String type, byte[] b) {
        if (StringUtils.isEmpty(type)) {
            return Bytes.toString(b);
        }

        try {
            switch (type.trim().toLowerCase()) {
                case "long":
                    return String.valueOf(Bytes.toLong(b));
                case "int":
                    return String.valueOf(Bytes.toInt(b));
                case "short":
                    return String.valueOf(Bytes.toShort(b));
                case "flout":
                    return String.valueOf(Bytes.toFloat(b));
                case "double":
                    return String.valueOf(Bytes.toDouble(b));
                case "bigdecimal":
                    return String.valueOf(Bytes.toBigDecimal(b));
                case "boolean":
                    return String.valueOf(Bytes.toBoolean(b));

                default:
                    return Bytes.toString(b);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "DATA CONVERSION EXCEPTION";
        }

    }

    public static HBasePageModel scanResultByPageFilter(String tableName, byte[] startRowKey, byte[] endRowKey,
                                                        List<Object> filtersObj, int maxVersions,
                                                        HBasePageModel pageModel, boolean firstPage,
                                                        Map<String, String> typeMapping) throws Exception {
        TableName habseTableName = TableName.valueOf(tableName);
        FilterList filterList = null;
        if (filtersObj != null && filtersObj.size() > 0) {
            List<Filter> realFilters = FilterFactory.filterConvert(filtersObj);
            if (realFilters != null && realFilters.size() > 0) {
                filterList = new FilterList(realFilters);
            }
        }

        if (pageModel == null) {
            pageModel = new HBasePageModel(10, tableName);
        }
        if (maxVersions <= 0) {
            // 默认只检索数据的最新版本
            maxVersions = Integer.MIN_VALUE;
        }
        pageModel.initStartTime();
        pageModel.initEndTime();
        if (tableName == null) {
            return pageModel;
        }
        Table table = null;
        List<Result> resultList = new ArrayList<Result>();

        try {
            Connection connection = getConn();
            table = connection.getTable(habseTableName);

            if (pageModel.getPageStartRowKey() == null && startRowKey != null) {
                pageModel.setPageStartRowKey(startRowKey);
            }

            if (pageModel.getPageStartRowKey() == null) {
                Result firstResult = selectFirstResultRow(habseTableName, filterList);
                if (firstResult == null || firstResult.isEmpty()) {
                    return pageModel;
                }
                startRowKey = firstResult.getRow();
                pageModel.setPageStartRowKey(startRowKey);
            }

            Scan scan = new Scan();
            scan.setCaching(100);
            scan.setStartRow(pageModel.getPageStartRowKey());
            if (pageModel.getMinStamp() != 0 && pageModel.getMaxStamp() != 0) {
                scan.setTimeRange(pageModel.getMinStamp(), pageModel.getMaxStamp());
            }

            if (pageModel.getPageEndRowKey() != null) {
                scan.setStopRow(pageModel.getPageEndRowKey());
            } else if (endRowKey != null) {
                scan.setStopRow(endRowKey);
            }

            PageFilter pageFilter = new PageFilter(firstPage ? pageModel.getPageSize() : (pageModel.getPageSize() + 1));// 第二页包含第一页的第一条数据，所以下一页要加+1
            if (filterList != null) {
                filterList.addFilter(pageFilter);
                scan.setFilter(filterList);
            } else {
                scan.setFilter(pageFilter);
            }
            if (maxVersions == Integer.MAX_VALUE) {
                scan.setMaxVersions();
            } else if (maxVersions == Integer.MIN_VALUE) {

            } else {
                scan.setMaxVersions(maxVersions);
            }
            long s = System.currentTimeMillis();
            ResultScanner scanner = table.getScanner(scan);
            System.out.println("scan耗时：" + (System.currentTimeMillis() - s));
            s = System.currentTimeMillis();
            int index = 0;
            for (Result rs : scanner.next(firstPage ? pageModel.getPageSize() : (pageModel.getPageSize() + 1))) {
                if (!firstPage && index == 0) {// 第二页包含第一页的第一条数据，所以这里要排除掉
                    index++;
                    continue;
                }
                if (!rs.isEmpty()) {
                    Row row = new Row(Bytes.toString(rs.getRow()));
                    for (Cell c : rs.rawCells()) {
                        byte[] family = CellUtil.cloneFamily(c);
                        byte[] qualifier = CellUtil.cloneQualifier(c);
                        row.add(family, qualifier,
                                new QualifierValue(qualifier,
                                                   getDisplayValue((typeMapping == null ? null : typeMapping.get(Bytes.toString(family)
                                                                                                                 + "."
                                                                                                                 + Bytes.toString(qualifier))),
                                                                   CellUtil.cloneValue(c))));
                    }
                    resultList.add(rs);
                    pageModel.addRow(row);
                }
            }
            scanner.close();
            System.out.println("数据组装耗时：" + (System.currentTimeMillis() - s));
        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        int pageIndex = pageModel.getPageIndex() + 1;
        pageModel.setPageIndex(pageIndex);
        if (resultList.size() > 0) {
            // 获取本次分页数据首行和末行的行键信息
            // byte[] pageStartRowKey = pageModel.getResultList().get(0).getRow();
            byte[] pageEndRowKey = resultList.get(resultList.size() - 1).getRow();
            pageModel.setPageStartRowKey(pageEndRowKey);
            pageModel.setPageEndRowKey(endRowKey);
        }
        pageModel.initEndTime();
        pageModel.printTimeInfo();
        return pageModel;

    }

    /**
     * 检索指定表的第一行记录。<br>
     * （如果在创建表时为此表指定了非默认的命名空间，则需拼写上命名空间名称，格式为【namespace:tablename】）。
     * 
     * @param tableName 表名称(*)。
     * @param filterList 过滤器集合，可以为null。
     * @return
     */
    public static Result selectFirstResultRow(TableName tableName, FilterList filterList) throws Exception {
        if (tableName == null) return null;
        Table table = null;
        try {
            Connection connection = getConn();
            table = connection.getTable(tableName);
            Scan scan = new Scan();
            if (filterList != null) {
                scan.setFilter(filterList);
            }
            ResultScanner scanner = table.getScanner(scan);
            Iterator<Result> iterator = scanner.iterator();
            int index = 0;
            if (iterator.hasNext()) {
                Result rs = iterator.next();
                if (index == 0) {
                    scanner.close();
                    return rs;
                }
            }
        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * 删除数据
     * 
     * @param tablename
     * @param rowkey
     */
    public static void deleteRow(String tablename, String... rowkey) throws Exception {
        Table table = null;
        try {
            TableName hbaseTableName = TableName.valueOf(tablename);
            Connection connection = getConn();
            table = connection.getTable(hbaseTableName);
            List<Delete> list = new ArrayList<Delete>();
            for (String item : rowkey) {
                list.add(new Delete(item.getBytes()));
            }

            table.delete(list);

        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 列出所有表名称
     * 
     * @return
     */
    public static String[] getListTableNames() throws Exception {
        Admin admin = null;
        try {
            Connection connection = getConn();
            admin = connection.getAdmin();
            TableName[] tables = admin.listTableNames();

            String[] result = new String[tables.length];
            for (int i = 0; i < tables.length; i++) {
                result[i] = tables[i].getNameAsString();
            }
            return result;
        } finally {
            try {
                admin.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 删除表
     * 
     * @param tablename
     * @throws IOException
     */
    public static void dropTable(String tablename) throws Exception {

        Admin hBaseAdmin = null;
        try {
            TableName hbaseTableName = TableName.valueOf(tablename);
            Connection connection = getConn();
            hBaseAdmin = connection.getAdmin();
            hBaseAdmin.disableTable(hbaseTableName);
            hBaseAdmin.deleteTable(hbaseTableName);
        } finally {
            if (hBaseAdmin != null) {
                try {
                    hBaseAdmin.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 清空表
     * 
     * @param tablename
     * @param preserveSplits
     */
    public static void truncateTable(String tablename, boolean preserveSplits) throws Exception {

        Admin hBaseAdmin = null;
        try {
            TableName hbaseTableName = TableName.valueOf(tablename);
            Connection connection = getConn();
            hBaseAdmin = connection.getAdmin();
            hBaseAdmin.disableTable(hbaseTableName);
            hBaseAdmin.truncateTable(hbaseTableName, preserveSplits);
            hBaseAdmin.enableTable(hbaseTableName);
        } finally {
            if (hBaseAdmin != null) {
                try {
                    hBaseAdmin.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 获取表结构
     * 
     * @param tablename
     * @return
     */
    public static HTableDescriptor getDescribe(TableName tablename) throws Exception {
        Table table = null;
        try {
            Connection connection = getConn();
            table = connection.getTable(tablename);
            return table.getTableDescriptor();

        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static List<HbaseQualifier> getTableQualifiers(String tableName) throws Exception {
        HBasePageModel dataModel = new HBasePageModel(1, tableName);
        dataModel = HbaseUtil.scanResultByPageFilter(tableName, null, null, null, Integer.MAX_VALUE, dataModel, true,
                                                     null);

        List<HbaseQualifier> result = new ArrayList<>();

        for (int i = 0; i < dataModel.getRowList().size(); i++) {
            Row row = dataModel.getRowList().get(i);
            Set<Map.Entry<byte[], ColumnFamily>> columnSet = row.getColumnFamilys().entrySet();// 所有列族
            for (Iterator iterator = columnSet.iterator(); iterator.hasNext();) {
                Entry<byte[], ColumnFamily> entry = (Entry<byte[], ColumnFamily>) iterator.next();// 某个列族的所有列
                for (Entry<byte[], QualifierValue> column : entry.getValue().getColumns().entrySet()) {
                    result.add(new HbaseQualifier(entry.getKey(), column.getKey(), "string"));
                }

            }
        }

        return result;

    }

    public static String getClusterStatus() throws Exception {
        Admin admin = null;
        try {
            Connection connection = getConn();
            admin = connection.getAdmin();
            return admin.getClusterStatus().toString();

        } finally {
            try {
                admin.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 计算表数据总数
     * 
     * @param tablename
     * @return
     */
    public static long rowCount(String tableName) throws Exception {
        Table table = null;
        long rowCount = 0;
        try {
            TableName hbaseTableName = TableName.valueOf(tableName);
            Connection connection = getConn();
            table = connection.getTable(hbaseTableName);
            Scan scan = new Scan();
            scan.setFilter(new FirstKeyOnlyFilter());
            ResultScanner resultScanner = table.getScanner(scan);
            for (Result result : resultScanner) {
                rowCount += result.size();
            }
        } finally {
            try {
                table.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return rowCount;
    }

    /**
     * 获取所有的namespace
     * 
     * @return
     * @throws Exception
     */
    public static Vector<String> listNameSpace() throws Exception {
        Vector<String> result = new Vector<>();
        Admin admin = null;
        try {
            Connection connection = getConn();
            admin = connection.getAdmin();
            NamespaceDescriptor[] namespaces = admin.listNamespaceDescriptors();
            for (NamespaceDescriptor item : namespaces) {
                result.add(item.getName());
            }
        } finally {
            try {
                admin.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    public static void createNameSpace(String name) throws Exception {
        Admin admin = null;
        try {
            Connection connection = getConn();
            admin = connection.getAdmin();
            admin.createNamespace(NamespaceDescriptor.create(name).build());
        } finally {
            try {
                admin.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void deleteNameSpace(String name) throws Exception {
        Admin admin = null;
        try {
            Connection connection = getConn();
            admin = connection.getAdmin();
            admin.deleteNamespace(name);
        } finally {
            try {
                admin.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
