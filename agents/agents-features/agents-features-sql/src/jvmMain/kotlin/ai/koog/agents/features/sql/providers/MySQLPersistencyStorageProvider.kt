package ai.koog.agents.features.sql.providers

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database

/**
 * MySQL-specific implementation of [ExposedPersistencyStorageProvider] for managing
 * agent checkpoints in MySQL databases.
 *
 * This provider is optimized for MySQL 5.7+ and MariaDB 10.2+, leveraging their
 * JSON column support for efficient checkpoint storage.
 *
 * ## Connection Options:
 * 1. JDBC URL: Direct connection string
 * 2. HikariCP: Advanced connection pooling with monitoring
 * 3. External DataSource: Integrate with existing connection pools
 *
 * ## MySQL Features:
 * - JSON column support for structured data
 * - Efficient indexing with composite keys
 * - Transaction support with proper isolation levels
 * - Compatible with MySQL replication for HA setups
 *
 * ## Example Usage:
 * ```kotlin
 * // Using JDBC URL
 * val provider = MySQLPersistencyStorageProvider(
 *     persistenceId = "my-agent",
 *     jdbcUrl = "jdbc:mysql://localhost:3306/mydb?useSSL=false&serverTimezone=UTC",
 *     username = "user",
 *     password = "pass",
 *     ttlSeconds = 3600
 * )
 *
 * // Using HikariCP configuration
 * val hikariConfig = HikariConfig().apply {
 *     jdbcUrl = "jdbc:mysql://localhost:3306/mydb"
 *     username = "user"
 *     password = "pass"
 *     maximumPoolSize = 10
 *     addDataSourceProperty("useSSL", "false")
 *     addDataSourceProperty("serverTimezone", "UTC")
 * }
 * val provider = MySQLPersistencyStorageProvider(
 *     persistenceId = "my-agent",
 *     hikariConfig = hikariConfig
 * )
 * ```
 *
 * @constructor Initializes the MySQL persistence provider with connection details.
 */
public class MySQLPersistencyStorageProvider : ExposedPersistencyStorageProvider {

    /**
     * Creates a provider with a JDBC URL and credentials.
     *
     * @param persistenceId Unique identifier for this agent's persistence data
     * @param jdbcUrl MySQL JDBC connection URL
     * @param username Database username
     * @param password Database password
     * @param tableName Name of the table to store checkpoints (default: "agent_checkpoints")
     * @param ttlSeconds Optional TTL for checkpoint entries in seconds
     */
    public constructor(
        persistenceId: String,
        jdbcUrl: String,
        username: String,
        password: String,
        tableName: String = "agent_checkpoints",
        ttlSeconds: Long? = null
    ) : super(
        persistenceId = persistenceId,
        database = Database.connect(
            url = jdbcUrl,
            driver = "com.mysql.cj.jdbc.Driver",
            user = username,
            password = password
        ),
        tableName = tableName,
        ttlSeconds = ttlSeconds
    )

    /**
     * Creates a provider with HikariCP configuration for advanced pooling.
     *
     * @param persistenceId Unique identifier for this agent's persistence data
     * @param hikariConfig HikariCP configuration
     * @param tableName Name of the table to store checkpoints
     * @param ttlSeconds Optional TTL for checkpoint entries in seconds
     */
    public constructor(
        persistenceId: String,
        hikariConfig: HikariConfig,
        tableName: String = "agent_checkpoints",
        ttlSeconds: Long? = null
    ) : super(
        persistenceId = persistenceId,
        database = Database.connect(HikariDataSource(hikariConfig)),
        tableName = tableName,
        ttlSeconds = ttlSeconds
    )

    /**
     * Creates a provider with an existing HikariDataSource.
     *
     * @param persistenceId Unique identifier for this agent's persistence data
     * @param dataSource Pre-configured HikariDataSource
     * @param tableName Name of the table to store checkpoints
     * @param ttlSeconds Optional TTL for checkpoint entries in seconds
     */
    public constructor(
        persistenceId: String,
        dataSource: HikariDataSource,
        tableName: String = "agent_checkpoints",
        ttlSeconds: Long? = null
    ) : super(
        persistenceId = persistenceId,
        database = Database.connect(dataSource),
        tableName = tableName,
        ttlSeconds = ttlSeconds
    )
}
