/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.graph.checkpoint.savers.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * PostgreSQL-specific implementation of JDBC checkpoint saver.
 *
 * @author yuluo-yx
 * @since 1.1.0.0-M4
 */
// PostgreSQL检查点保存器类，继承自AbstractJdbcSaver
public class PostgresqlSaver extends AbstractJdbcSaver {

	/**
	 * Constructs a PostgresqlSaver with the given DataSource.
	 * @param dataSource the JDBC DataSource to use for database connections
	 */
	// 使用给定的数据源构造PostgresqlSaver
	public PostgresqlSaver(DataSource dataSource) {
		// 调用父类构造函数
		super(dataSource);
	}

	/**
	 * Constructs a PostgresqlSaver with the given DataSource and ObjectMapper.
	 * @param dataSource the JDBC DataSource to use for database connections
	 * @param objectMapper the ObjectMapper for JSON serialization
	 */
	// 使用给定的数据源和对象映射器构造PostgresqlSaver
	public PostgresqlSaver(DataSource dataSource, ObjectMapper objectMapper) {
		// 调用父类构造函数
		super(dataSource, objectMapper);
	}

	/**
	 * Constructs a PostgresqlSaver with custom table name.
	 * @param dataSource the JDBC DataSource to use for database connections
	 * @param objectMapper the ObjectMapper for JSON serialization
	 * @param tableName the name of the database table to store checkpoints
	 */
	// 使用自定义表名构造PostgresqlSaver
	public PostgresqlSaver(DataSource dataSource, ObjectMapper objectMapper, String tableName) {
		// 调用父类构造函数
		super(dataSource, objectMapper, tableName);
	}

	// 重写创建表SQL的方法
	@Override
	protected String getCreateTableSql() {
		// 返回PostgreSQL格式的创建表SQL语句
		return """
				CREATE TABLE IF NOT EXISTS %s (
					thread_id VARCHAR(255) PRIMARY KEY,
					checkpoint_data TEXT NOT NULL,
					updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
				)
				""".formatted(tableName);
	}

	// 重写插入SQL的方法
	@Override
	protected String getInsertSql() {
		// PostgreSQL's UPSERT syntax for atomic insert or update
		// PostgreSQL的UPSERT语法，用于原子插入或更新
		return """
				INSERT INTO %s (thread_id, checkpoint_data, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP)
				ON CONFLICT (thread_id)
				DO UPDATE SET checkpoint_data = EXCLUDED.checkpoint_data, updated_at = CURRENT_TIMESTAMP
				""".formatted(tableName);
	}

}
