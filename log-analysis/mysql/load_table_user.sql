
LOAD DATA LOCAL INFILE '/bigdata/git/AuraCasesTraining/log-analysis/datasource/user.list' INTO TABLE log.user_dimension
FIELDS TERMINATED BY ',' ENCLOSED BY '"'
LINES TERMINATED BY '\n'
