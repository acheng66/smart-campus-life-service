package com.smartcampus.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Agent RAG 的独立 pgvector 数据源。
 *
 * <p>业务 MySQL 仍由 Spring 的主数据源管理；这里显式创建 PostgreSQL 数据源，避免向量表被建到 MySQL。
 * 该配置仅在同时启用 RAG 和 EmbeddingModel 时生效。向量库只保存可检索的文本知识，
 * 库存、资格和订单仍以业务库的实时查询为准。</p>
 */
@Configuration
@EnableAsync
@ConditionalOnProperty(prefix = "agent.rag", name = "enabled", havingValue = "true")
public class AgentRagConfig {

    /**
     * 单线程有界队列保证同一店铺/券的增量事件按提交顺序写入，避免默认异步执行器无限创建线程。
     */
    @Bean("agentRagTaskExecutor")
    public Executor agentRagTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-rag-index-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    public AgentRagDataSource agentRagDataSource(
            @Value("${agent.rag.datasource.url}") String url,
            @Value("${agent.rag.datasource.username}") String username,
            @Value("${agent.rag.datasource.password}") String password) {
        // 不将此连接池直接注册为 DataSource Bean，避免 Spring Boot 误将其识别为 MySQL 主数据源。
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
        dataSource.setPoolName("agent-rag-pgvector");
        return new AgentRagDataSource(dataSource);
    }

    @Bean("agentRagJdbcTemplate")
    public JdbcTemplate agentRagJdbcTemplate(AgentRagDataSource agentRagDataSource) {
        // PgVectorStore 通过该 JdbcTemplate 建表、写入向量和执行相似度检索。
        return new JdbcTemplate(agentRagDataSource.dataSource());
    }

    @Bean("campusAgentVectorStore")
    public VectorStore campusAgentVectorStore(@Qualifier("agentRagJdbcTemplate") JdbcTemplate agentRagJdbcTemplate,
            EmbeddingModel embeddingModel,
            @Value("${agent.rag.schema-name:public}") String schemaName,
            @Value("${agent.rag.table-name:agent_knowledge_vector}") String tableName,
            @Value("${agent.rag.dimensions:1024}") int dimensions,
            @Value("${agent.rag.initialize-schema:true}") boolean initializeSchema) {
        // schema/table 会拼入 SQL 标识符，限制格式可防止错误配置造成 SQL 注入。
        validateIdentifier(schemaName, "schema-name");
        validateIdentifier(tableName, "table-name");
        return PgVectorStore.builder(agentRagJdbcTemplate, embeddingModel)
                // 当前知识文档使用 shop-1、voucher-10 等可读 ID，而非 UUID。
                .idType(PgVectorStore.PgIdType.TEXT)
                .schemaName(schemaName)
                .vectorTableName(tableName)
                // 必须与 SiliconFlow Embedding 的输出维度一致。
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                // 首次启动时创建 vector/hstore 扩展、表与索引；生产可交给 DBA 后关闭。
                .initializeSchema(initializeSchema)
                .build();
    }

    private void validateIdentifier(String value, String propertyName) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("agent.rag." + propertyName + " 只能包含字母、数字和下划线，且不能以数字开头");
        }
    }

    /**
     * pgvector 连接池的生命周期包装器。
     *
     * <p>它刻意不实现 {@link javax.sql.DataSource}，因此不会干扰 MySQL 主数据源自动配置；
     * Spring 容器关闭时会调用 {@link #close()} 释放连接。</p>
     */
    public static final class AgentRagDataSource implements AutoCloseable {
        private final HikariDataSource dataSource;

        private AgentRagDataSource(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }

        private HikariDataSource dataSource() {
            return dataSource;
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
