package org.example.portfolio_management_system;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/javafxapp2"; // Update as necessary
    private static final String USER = "root"; // Your MySQL username
    private static final String PASSWORD = "Servesh#21"; // Your MySQL password

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}