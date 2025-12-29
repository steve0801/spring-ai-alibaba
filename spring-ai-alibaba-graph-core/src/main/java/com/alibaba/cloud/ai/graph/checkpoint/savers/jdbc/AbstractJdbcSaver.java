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

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * Abstract base class for JDBC-based checkpoint savers.
 * such as: PGSQL, MySQL, H2 and etc.
 *
 * @author yuluo-yx
 * @since 1.1.0.0-M4
 */
// JDBC检查点保存器的抽象基类，实现了BaseCheckpointSaver接口
public abstract class AbstractJdbcSaver implements BaseCheckpointSaver {

	// 日志记录器，用于记录日志信息
	private static final Logger logger = LoggerFactory.getLogger(AbstractJdbcSaver.class);

	// 数据源，用于获取数据库连接
	protected final DataSource dataSource;

	// 对象映射器，用于JSON序列化和反序列化
	protected final ObjectMapper objectMapper;

	// 表名，用于存储检查点的数据库表名
	protected final String tableName;

	/**
	 * Constructs an AbstractJdbcSaver with default table name.
	 *
	 * @param dataSource the JDBC DataSource to use for database connections
	 */
	// 使用默认表名构造AbstractJdbcSaver
	protected AbstractJdbcSaver(DataSource dataSource) {
		// 调用带自定义参数的构造函数，使用默认的ObjectMapper和表名
		this(dataSource, new ObjectMapper(), "checkpoint_store");
	}

	/**
	 * Constructs an AbstractJdbcSaver with custom ObjectMapper.
	 *
	 * @param dataSource   the JDBC DataSource to use for database connections
	 * @param objectMapper the ObjectMapper for JSON serialization
	 */
	// 使用自定义ObjectMapper构造AbstractJdbcSaver
	protected AbstractJdbcSaver(DataSource dataSource, ObjectMapper objectMapper) {
		// 调用带自定义参数的构造函数，使用默认的表名
		this(dataSource, objectMapper, "checkpoint_store");
	}

	/**
	 * Constructs an AbstractJdbcSaver with custom table name.
	 *
	 * @param dataSource   the JDBC DataSource to use for database connections
	 * @param objectMapper the ObjectMapper for JSON serialization
	 * @param tableName    the name of the database table to store checkpoints
	 */
	// 使用自定义表名构造AbstractJdbcSaver
	protected AbstractJdbcSaver(DataSource dataSource, ObjectMapper objectMapper, String tableName) {
		// 设置数据源，确保不为null
		this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
		// 配置对象映射器
		this.objectMapper = configureObjectMapper(objectMapper);
		// 设置表名，确保不为null
		this.tableName = Objects.requireNonNull(tableName, "tableName cannot be null");
		// 初始化数据库表
		initializeTable();
	}

	// 配置对象映射器的私有静态方法
	private static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
		// Reuse BaseCheckpointSaver's configuration to ensure consistent Message
		// serialization
		// 重用BaseCheckpointSaver的配置以确保消息序列化的一致性
		return BaseCheckpointSaver.configureObjectMapper(objectMapper);
	}

	/**
	 * Returns the SQL statement to create the checkpoint table.
	 * Subclasses should provide database-specific DDL.
	 *
	 * @return the CREATE TABLE SQL statement
	 */
	// 返回创建检查点表的SQL语句，子类应提供特定数据库的DDL
	protected abstract String getCreateTableSql();

	/**
	 * Returns the SQL statement to select checkpoint data by thread ID.
	 *
	 * @return the SELECT SQL statement
	 */
	// 返回根据线程ID选择检查点数据的SQL语句
	protected String getSelectSql() {
		// 返回格式化的SELECT SQL语句
		return "SELECT checkpoint_data FROM %s WHERE thread_id = ?".formatted(tableName);
	}

	/**
	 * Returns the SQL statement to insert a new checkpoint record.
	 *
	 * @return the INSERT SQL statement
	 */
	// 返回插入新检查点记录的SQL语句，这是一个抽象方法，需要子类实现
	protected abstract String getInsertSql();

	/**
	 * Returns the SQL statement to update an existing checkpoint record.
	 *
	 * @return the UPDATE SQL statement
	 */
	// 返回更新现有检查点记录的SQL语句
	protected String getUpdateSql() {
		// 返回格式化的UPDATE SQL语句
		return """
				UPDATE %s
				SET checkpoint_data = ?, updated_at = CURRENT_TIMESTAMP
				WHERE thread_id = ?
				""".formatted(tableName);
	}

	/**
	 * Returns the SQL statement to delete checkpoint data by thread ID.
	 *
	 * @return the DELETE SQL statement
	 */
	// 返回根据线程ID删除检查点数据的SQL语句
	protected String getDeleteSql() {
		// 返回格式化的DELETE SQL语句
		return "DELETE FROM %s WHERE thread_id = ?".formatted(tableName);
	}

	/**
	 * Initializes the database table if it doesn't exist.
	 */
	// 如果数据库表不存在则初始化数据库表
	protected void initializeTable() {
		// 使用try-with-resources语句获取连接和创建语句
		try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			// 执行创建表的SQL语句
			stmt.execute(getCreateTableSql());
			// 记录表初始化成功的调试日志
			logger.debug("Checkpoint table '{}' initialized successfully", tableName);
		} catch (SQLException e) {
			// 如果初始化失败，抛出运行时异常
			throw new RuntimeException("Failed to initialize checkpoint table: " + tableName, e);
		}
	}

	// 重写list方法，根据配置列出检查点
	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		// 获取线程ID的可选值
		Optional<String> threadIdOpt = config.threadId();
		// 检查线程ID是否为空
		if (threadIdOpt.isEmpty()) {
			// 如果为空，抛出参数异常
			throw new IllegalArgumentException("threadId is not allowed to be null");
		}

		// 获取线程ID
		String threadId = threadIdOpt.get();

		// 使用try-with-resources获取数据库连接和准备SQL语句
		try (Connection conn = dataSource.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(getSelectSql())) {

			// 设置SQL参数
			pstmt.setString(1, threadId);
			// 执行查询
			try (ResultSet rs = pstmt.executeQuery()) {
				// 检查结果集是否有下一行
				if (rs.next()) {
					// 获取检查点JSON字符串
					String checkpointJson = rs.getString("checkpoint_data");
					// 将JSON反序列化为检查点列表
					List<Checkpoint> checkpoints = objectMapper.readValue(checkpointJson, new TypeReference<>() {
					});
					// 返回检查点列表
					return checkpoints;
				}
				// 如果没有结果，返回空列表
				return Collections.emptyList();
			}
		} catch (SQLException e) {
			// 处理SQL异常
			throw new RuntimeException("Failed to list checkpoints for threadId: " + threadId, e);
		} catch (JsonProcessingException e) {
			// 处理JSON处理异常
			throw new RuntimeException("Failed to deserialize checkpoint data", e);
		}
	}

	// 重写get方法，根据配置获取检查点
	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		// 获取线程ID的可选值
		Optional<String> threadIdOpt = config.threadId();
		// 检查线程ID是否为空
		if (threadIdOpt.isEmpty()) {
			// 如果为空，抛出参数异常
			throw new IllegalArgumentException("threadId is not allowed to be null");
		}

		// 获取线程ID
		String threadId = threadIdOpt.get();

		// 使用try-with-resources获取数据库连接和准备SQL语句
		try (Connection conn = dataSource.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(getSelectSql())) {

			// 设置SQL参数
			pstmt.setString(1, threadId);
			// 执行查询
			try (ResultSet rs = pstmt.executeQuery()) {
				// 检查结果集是否有下一行
				if (rs.next()) {
					// 获取检查点JSON字符串
					String checkpointJson = rs.getString("checkpoint_data");
					// 将JSON反序列化为检查点列表
					List<Checkpoint> checkpoints = objectMapper.readValue(checkpointJson, new TypeReference<>() {
					});

					// 如果请求了特定的检查点ID
					if (config.checkPointId().isPresent()) {
						// 返回与指定ID匹配的检查点
						return config.checkPointId()
								.flatMap(id -> checkpoints.stream()
										.filter(checkpoint -> checkpoint.getId().equals(id))
										.findFirst());
					}

					// 返回最新的检查点（列表中的第一个）
					return getLast(getLinkedList(checkpoints), config);
				}
				// 如果没有结果，返回空的Optional
				return Optional.empty();
			}
		} catch (SQLException e) {
			// 处理SQL异常
			throw new RuntimeException("Failed to get checkpoint for threadId: " + threadId, e);
		} catch (JsonProcessingException e) {
			// 处理JSON处理异常
			throw new RuntimeException("Failed to deserialize checkpoint data", e);
		}
	}

	// 重写put方法，将检查点保存到数据库
	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		// 获取线程ID的可选值
		Optional<String> threadIdOpt = config.threadId();
		// 检查线程ID是否为空
		if (threadIdOpt.isEmpty()) {
			// 如果为空，抛出参数异常
			throw new IllegalArgumentException("threadId is not allowed to be null");
		}

		// 获取线程ID
		String threadId = threadIdOpt.get();

		// 使用try-with-resources获取数据库连接
		try (Connection conn = dataSource.getConnection()) {
			// 关闭自动提交
			conn.setAutoCommit(false);
			// 在try块中执行数据库操作
			try {
				// 读取现有的检查点
				LinkedList<Checkpoint> checkpoints;
				// 标记记录是否存在
				boolean recordExists = false;
				// 使用try-with-resources准备SQL语句
				try (PreparedStatement pstmt = conn.prepareStatement(getSelectSql())) {
					// 设置SQL参数
					pstmt.setString(1, threadId);
					// 执行查询
					try (ResultSet rs = pstmt.executeQuery()) {
						// 检查结果集是否有下一行
						if (rs.next()) {
							// 标记记录存在
							recordExists = true;
							// 获取检查点JSON字符串
							String checkpointJson = rs.getString("checkpoint_data");
							// 将JSON反序列化为检查点列表
							List<Checkpoint> existingList = objectMapper.readValue(checkpointJson,
									new TypeReference<>() {
									});
							// 转换为链表
							checkpoints = getLinkedList(existingList);
						} else {
							// 如果没有结果，创建新的空链表
							checkpoints = new LinkedList<>();
						}
					}
				}

				// 更新或插入检查点
				if (config.checkPointId().isPresent()) {
					// 替换现有的检查点
					String checkPointId = config.checkPointId().get();
					// 查找匹配的检查点索引
					int index = IntStream.range(0, checkpoints.size())
							.filter(i -> checkpoints.get(i).getId().equals(checkPointId))
							.findFirst()
							.orElseThrow(() -> new NoSuchElementException(
									format("Checkpoint with id %s not found!", checkPointId)));
					// 设置新的检查点
					checkpoints.set(index, checkpoint);
				} else {
					// 将新检查点添加到前面
					checkpoints.push(checkpoint);
				}

				// 使用UPDATE或INSERT将数据保存回数据库
				String checkpointJson = objectMapper.writeValueAsString(checkpoints);
				if (recordExists) {
					// 更新现有记录
					try (PreparedStatement pstmt = conn.prepareStatement(getUpdateSql())) {
						// 设置更新参数
						pstmt.setString(1, checkpointJson);
						pstmt.setString(2, threadId);
						// 执行更新
						pstmt.executeUpdate();
					}
				} else {
					// 插入新记录
					try (PreparedStatement pstmt = conn.prepareStatement(getInsertSql())) {
						// 设置插入参数
						setInsertParameters(pstmt, threadId, checkpointJson);
						// 执行插入
						pstmt.executeUpdate();
					}
				}

				// 提交事务
				conn.commit();

				// 检查配置中是否有检查点ID
				if (config.checkPointId().isPresent()) {
					// 返回包含新检查点ID的配置
					return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
				}
				// 返回包含新检查点ID的配置
				return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
			} catch (Exception e) {
				// 发生异常时回滚事务
				conn.rollback();
				// 重新抛出异常
				throw e;
			} finally {
				// 确保自动提交被重新启用
				conn.setAutoCommit(true);
			}
		} catch (SQLException e) {
			// 处理SQL异常
			throw new RuntimeException("Failed to put checkpoint for threadId: " + threadId, e);
		}
	}

	/**
	 * Sets the parameters for the INSERT statement.
	 * Subclasses can override this if they need different parameter ordering.
	 *
	 * @param pstmt          the PreparedStatement
	 * @param threadId       the thread ID
	 * @param checkpointJson the serialized checkpoint data
	 * @throws SQLException if a database access error occurs
	 */
	// 为INSERT语句设置参数，如果子类需要不同的参数顺序，可以重写此方法
	protected void setInsertParameters(PreparedStatement pstmt, String threadId, String checkpointJson)
			throws SQLException {
		// 设置线程ID参数
		pstmt.setString(1, threadId);
		// 设置检查点JSON参数
		pstmt.setString(2, checkpointJson);
	}

	// 重写clear方法，清除指定配置的检查点
	@Override
	public boolean clear(RunnableConfig config) {
		// 获取线程ID的可选值
		Optional<String> threadIdOpt = config.threadId();
		// 检查线程ID是否为空
		if (threadIdOpt.isEmpty()) {
			// 如果为空，抛出参数异常
			throw new IllegalArgumentException("threadId is not allowed to be null");
		}

		// 获取线程ID
		String threadId = threadIdOpt.get();

		// 使用try-with-resources获取数据库连接和准备SQL语句
		try (Connection conn = dataSource.getConnection();
				PreparedStatement pstmt = conn.prepareStatement(getDeleteSql())) {

			// 设置SQL参数
			pstmt.setString(1, threadId);
			// 执行删除并获取受影响的行数
			int rowsAffected = pstmt.executeUpdate();
			// 返回是否成功删除（有行被影响）
			return rowsAffected > 0;
		} catch (SQLException e) {
			// 处理SQL异常
			throw new RuntimeException("Failed to clear checkpoints for threadId: " + threadId, e);
		}
	}

}
