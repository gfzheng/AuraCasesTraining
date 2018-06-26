#!/bin/bash

sqoop import -Dorg.apache.sqoop.splitter.allow_text_splitter=true --connect jdbc:mysql://c7402/log --username aura --password aura --table user_dimension --driver com.mysql.jdbc.Driver --m 10 --target-dir /warehouse/user_dimension
