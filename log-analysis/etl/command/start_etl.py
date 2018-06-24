#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys,os,time

INTPUT_PATH="hdfs://c7401:9000/flume/record/"
OUTPUT_PATH="hdfs://c7401:9000/etl/record/"
LOAD_CMD="java -cp ../loadDataToHive/target/etl-1.0-SNAPSHOT-jar-with-dependencies.jar \
bigdata.etl.loadDataToHive %s  %s  %s"
HADOOP_CMD="hadoop jar /usr/local/hadoop-2.7.5/share/hadoop/tools/lib/hadoop-streaming-2.7.5.jar \
-D mapred.reduce.tasks=0 -D mapred.map.tasks=1  -input %s -output %s \
-mapper /bigdata/git/AuraCasesTraining/log-analysis/etl/command/etl.py \
-file /bigdata/git/AuraCasesTraining/log-analysis/etl/command/etl.py"

def getCurrentYmdHM():
    time_struct=time.localtime(time.time()-60*10)

    H= time.strftime('%H',time_struct)
    M= int(time.strftime('%M',time_struct))
    M= "%02d" % ((M/10)*10)
    Ymd=time.strftime('%Y-%m-%d',time_struct)

    return Ymd+"/"+H+M

def startETL():
    subPath=getCurrentYmdHM()
    input=INTPUT_PATH+subPath
    output=OUTPUT_PATH+subPath

    hadoop_cmd=HADOOP_CMD %(input, output)
    print hadoop_cmd
    os.system(hadoop_cmd)
    print 'loading data into Hive'
    load_cmd= LOAD_CMD %(output,subPath.split("/")[0],subPath.split("/")[1])
    os.system(load_cmd)

def startETLForPath(subPath):
    input=INTPUT_PATH+subPath
    output=OUTPUT_PATH+subPath

    hadoop_cmd=HADOOP_CMD %(input, output)
    print hadoop_cmd
    os.system(hadoop_cmd)
    print 'loading data into Hive'
    load_cmd= LOAD_CMD %(output,subPath.split("/")[0],subPath.split("/")[1])
    os.system(load_cmd)


if __name__ == '__main__':
    if len(sys.argv) > 1:
        Ymd=sys.argv[1]
        HM=sys.argv[2]
        timestring = Ymd + " " + HM
        t = time.mktime(time.strptime(timestring, '%Y-%m-%d %H%M'))
        for index in range(40):
            subPath = time.strftime('%Y-%m-%d/%H%M',time.localtime(t))
            startETLForPath(subPath)
            t = t + 600
    else:
        startETL()