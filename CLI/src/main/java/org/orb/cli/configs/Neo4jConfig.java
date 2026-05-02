package org.orb.cli.configs;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

public class Neo4jConfig {

    private final String host;
    private final String username;
    private final String password;

    public Neo4jConfig(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
    }

    public Driver neo4jDriver() {
        return GraphDatabase.driver(host, AuthTokens.basic(username, password));
    }

    public void closeDriver(Driver driver) {
        if (driver != null) {
            driver.close();
        }
    }
}
