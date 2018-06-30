package com.aura.spark.streaming;

import com.alibaba.fastjson.JSON;
import com.aura.dao.JavaDBDao;
import com.aura.db.DBHelper;
import com.aura.model.Log;
import com.aura.util.StringUtil;
import com.aura.spark.core.JavaContentAnalysis;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import kafka.serializer.StringDecoder;
import org.apache.spark.SparkConf;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class JavaStreamingAnalysis {
    private static final Logger LOG = LoggerFactory.getLogger(JavaContentAnalysis.class);

    private Config config;
    private JavaStreamingContext ssc;

    public JavaStreamingAnalysis() {
        config = ConfigFactory.parseResources("aura.conf");
    }

    private JavaStreamingContext createStreamingContext(Config config) {
        SparkConf conf = new SparkConf();
        conf.setAppName("Java Streaming Analysis");
        conf.set("spark.streaming.stopGracefullyOnShutdown", "true");
        Duration batchInterval = Durations.seconds(config.getLong("streaming.interval"));
        JavaStreamingContext ssc = new JavaStreamingContext(conf, batchInterval);
        return ssc;
    }

    private Map<String, String> getKafkaParams() {
        Map<String, String> params = new HashMap<String, String>();
        Config kafkaConfig = config.getConfig("kafka");
        params.put("metadata.broker.list", kafkaConfig.getString("metadata.broker.list"));
        params.put("auto.offset.reset", kafkaConfig.getString("auto.offset.reset"));
        params.put("group.id", kafkaConfig.getString("group.id"));
        return params;
    }

    public void runAnalysis() throws InterruptedException {
        ssc = createStreamingContext(config);
        ssc.sparkContext().setLogLevel("WARN");
        String topic = config.getString("streaming.topic");
        JavaPairInputDStream<String, String> input = KafkaUtils.createDirectStream(
                ssc,
                String.class,
                String.class,
                StringDecoder.class,
                StringDecoder.class,
                getKafkaParams(),
                Sets.newHashSet(topic));
        JavaDStream<Log> logs = input
                .map(t -> JSON.parseObject(t._2, Log.class))
                .filter(Log::isLegal);
        logs.cache();

        processByProvince(logs);
        processByContent(logs);

        ssc.start();
        ssc.awaitTermination();
    }

    private void processByProvince(JavaDStream<Log> logs) {
        Map<String, Integer> provinceMap = JavaDBDao.getProvinceMap();
        Broadcast<Map<String, Integer>> broadcastMap = ssc.sparkContext().broadcast(provinceMap);
        JavaDStream<LogRecord> records = logs.map(log -> {
            int dimId = broadcastMap.value().getOrDefault(log.getArea(), 0);
            return new LogRecord(dimId,
                    secondsOfDay(log.getTs()),
                    log.getUuid(),
                    log.getIp(),
                    log.getUrl(),
                    log.getTitle(),
                    log.getContentId(),
                    log.getArea());
        });
        records.foreachRDD(rdd -> {
            SparkSession spark = SparkSession.builder()
                    .config(rdd.context().getConf())
                    .getOrCreate();
            Dataset<Row> df = spark.createDataFrame(rdd, LogRecord.class);
            df.createOrReplaceTempView("logs");
            Dataset<Row> provinceCounts = spark.sql("SELECT dimId,second,COUNT(1) AS pv,COUNT(DISTINCT uuid) AS uv FROM logs GROUP BY dimId,second");
            provinceCounts.foreachPartition(rows -> {
                Connection conn = DBHelper.getConnection();
                rows.forEachRemaining(row -> {
                    try {
                        JavaDBDao.saveStreamingDimensionCount(conn, row.getInt(0), row.getInt(1), row.getLong(2), row.getLong(3));
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
                conn.close();
            });
        });
    }

    private void processByContent(JavaDStream<Log> logs) {
        JavaDStream<LogRecord> records = logs
                .filter(log -> log.getClearTitle() != null && log.getClearTitle().length() > 5)
                .map(log -> {
                    return new LogRecord(
                            secondsOfDay(log.getTs()),
                            log.getUuid(),
                            log.getIp(),
                            log.getUrl(),
                            log.getClearTitle(),
                            log.getContentId(),
                            log.getArea());
                });
        records.foreachRDD(rdd -> {
            SparkSession spark = SparkSession.builder()
                    .config(rdd.context().getConf())
                    .getOrCreate();
            Dataset<Row> df = spark.createDataFrame(rdd, LogRecord.class);
            df.createOrReplaceTempView("logs");
            Dataset<Row> contentCounts = spark.sql("SELECT contentId, second, COUNT(1) AS pv, COUNT(DISTINCT uuid) AS uv FROM logs GROUP BY contentId,second");
            contentCounts.foreachPartition(rows -> {
                Connection conn = DBHelper.getConnection();
                rows.forEachRemaining(row -> {
                    try {
                        JavaDBDao.saveStreamingContentCount(conn, row.getLong(0), row.getInt(1), row.getLong(2), row.getLong(3));
                    } catch (SQLException e) {
                        LOG.error("save streaming content count failed", e);
                    }
                });
                conn.close();
            });
            Dataset<Row> contentDetails = spark.sql("SELECT DISTINCT contentId, url, title FROM logs");
            contentDetails.foreachPartition(rows -> {
                Connection conn = DBHelper.getConnection();
                rows.forEachRemaining(row -> {
                    try {
                        String url = StringUtil.limitString(row.getString(1), 500, "utf8");
                        String title = StringUtil.limitString(row.getString(2), 500, "utf8");
                        JavaDBDao.saveStreamingContentDetail(conn, row.getLong(0), url, title);
                    } catch (SQLException e) {
                        LOG.error("save content detail failed", e);
                    }
                });
                conn.close();
            });
        });
    }

    public static int secondsOfDay(long seconds) {
        return (int) (seconds % 86400);
    }

    /**
     * $SPARK_HOME/bin/spark-submit \
     * --master yarn-cluster \
     * --class com.aura.spark.streaming.JavaStreamingAnalysis \
     * --conf spark.streaming.kafka.maxRatePerPartition=200 \
     * --conf spark.sql.shuffle.partitions=10 \
     * $jar_file
     */
    public static void main(String[] args) {
        JavaStreamingAnalysis streaming = new JavaStreamingAnalysis();
        try {
            streaming.runAnalysis();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
