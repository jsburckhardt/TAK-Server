package tak.server.util;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bbn.marti.config.Repository;
import com.bbn.marti.config.Connection;
import com.bbn.marti.remote.CoreConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


public class DataSourceUtils {
	
	private static final Logger logger = LoggerFactory.getLogger(DataSourceUtils.class);

	public static HikariDataSource setupDataSourceFromCoreConfig(CoreConfig coreConfig) {
		
	    Repository repository = coreConfig.getRemoteConfiguration().getRepository();
	    Connection coreDbConnection = repository.getConnection();

        int max_connections = 0;
	    if (repository.isEnable()) {
	        Properties props = new Properties();
	        props.setProperty("user", coreDbConnection.getUsername());
	        props.setProperty("password", coreDbConnection.getPassword());
	        try(java.sql.Connection conn = DriverManager.getConnection(coreDbConnection.getUrl(), props); ResultSet res = conn.createStatement().executeQuery("show max_connections")) {
	            
	            res.next();
	            max_connections = res.getInt(1);
	        } catch (Exception ee) {
	            logger.error("error connecting to database", ee);
	        }
	    }

        // this will set a min of 200 connections for 4 cpus (c5.xlarge) to 1045 for 96
        // cpus (c5.24xlarge) (max is 1045 regardless)
        int numDbConnections;
        if (repository.isConnectionPoolAutoSize()) {
            numDbConnections = coreConfig.getRemoteConfiguration().getRepository().getPoolScaleFactor() + (int) Math.min(845, (Runtime.getRuntime().availableProcessors() - 4) * 9.2);
            numDbConnections = Math.min(Math.max(1, (max_connections - 2) / 2), numDbConnections);
        } else {
            numDbConnections = repository.getNumDbConnections();
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("There are: " + Runtime.getRuntime().availableProcessors()
                    + " cpus currently available; The max connections is: " + max_connections
                    + " and the computed connection pool size is: " + numDbConnections);
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("We are setting up a connection pool of size: " + numDbConnections);
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setUsername(coreDbConnection.getUsername());
        hikariConfig.setPassword(coreDbConnection.getPassword());
        hikariConfig.setJdbcUrl(coreDbConnection.getUrl());
        hikariConfig.setMaxLifetime(repository.getDbConnectionMaxLifetimeMs());
        hikariConfig.setIdleTimeout(repository.getDbConnectionMaxIdleMs());
        hikariConfig.setMaximumPoolSize(numDbConnections);
        hikariConfig.setConnectionTimeout(repository.getDbTimeoutMs());
        hikariConfig.setAllowPoolSuspension(true);
        hikariConfig.setInitializationFailTimeout(-1);
        hikariConfig.setMinimumIdle(1);

        if (!repository.isEnable()) {
                // Zero would be ideal.... But an exception is thrown if less than 250 is chosen
        	hikariConfig.setConnectionTimeout(250);
        }

        HikariDataSource hds = new HikariDataSource(hikariConfig);
        return hds;
	}
	
}
