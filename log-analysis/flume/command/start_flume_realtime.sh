#!/bin/bash

flume-ng agent --conf /usr/local/apache-flume-1.8.0-bin/conf \
--conf-file ../conf/flume-conf-logAnalysis-kafka.properties \
--name logAgent -Dflume.root.logger=DEBUG,console -Dflume.monitoring.type=http -Dflume.monitoring.port=34546
