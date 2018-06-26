LOAD DATA LOCAL INFILE '/bigdata/git/AuraCasesTraining/log-analysis/datasource/brand.list' INTO TABLE log.brand_dimension
FIELDS TERMINATED BY ','
LINES TERMINATED BY '\n'
