package com.nbabot.database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private final String dbPath;
    private Connection connection;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
        initDatabase();
    }

    private void initDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createTables();
            System.out.println("Database inizializzato con successo!");
        } catch (SQLException e) {
            System.err.println("Errore nell'inizializzazione del database: " + e.getMessage());
        }
    }

    private void createTables() {
        String createUsersTable = """
            CREATE TABLE IF NOT EXISTS users (
                user_id INTEGER PRIMARY KEY,
                first_name TEXT NOT NULL,
                last_name TEXT,
                username TEXT,
                registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_interaction TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;

        String createFavoritesTable = """
            CREATE TABLE IF NOT EXISTS favorites (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                favorite_type TEXT NOT NULL,
                item_id INTEGER NOT NULL,
                item_name TEXT NOT NULL,
                item_data TEXT,
                saved_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(user_id),
                UNIQUE(user_id, favorite_type, item_id)
            )
        """;

        String createSearchHistoryTable = """
            CREATE TABLE IF NOT EXISTS search_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                search_type TEXT NOT NULL,
                search_query TEXT NOT NULL,
                search_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(user_id)
            )
        """;

        String createStatsTable = """
            CREATE TABLE IF NOT EXISTS user_statistics (
                user_id INTEGER PRIMARY KEY,
                total_searches INTEGER DEFAULT 0,
                player_searches INTEGER DEFAULT 0,
                team_searches INTEGER DEFAULT 0,
                total_favorites INTEGER DEFAULT 0,
                FOREIGN KEY (user_id) REFERENCES users(user_id)
            )
        """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createFavoritesTable);
            stmt.execute(createSearchHistoryTable);
            stmt.execute(createStatsTable);
        } catch (SQLException e) {
            System.err.println("Errore nella creazione delle tabelle: " + e.getMessage());
        }
    }

    public void registerUser(long userId, String firstName, String lastName, String username) {
        String sql = """
            INSERT OR IGNORE INTO users (user_id, first_name, last_name, username)
            VALUES (?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, firstName);
            pstmt.setString(3, lastName);
            pstmt.setString(4, username);
            pstmt.executeUpdate();

            // Crea anche le statistiche per l'utente
            String statsSql = "INSERT OR IGNORE INTO user_statistics (user_id) VALUES (?)";
            try (PreparedStatement statsPstmt = connection.prepareStatement(statsSql)) {
                statsPstmt.setLong(1, userId);
                statsPstmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("Errore nella registrazione utente: " + e.getMessage());
        }
    }

    public void updateLastInteraction(long userId) {
        String sql = "UPDATE users SET last_interaction = CURRENT_TIMESTAMP WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore nell'aggiornamento interazione: " + e.getMessage());
        }
    }

    public boolean saveFavorite(long userId, String type, int itemId, String itemName, String itemData) {
        String sql = """
            INSERT OR REPLACE INTO favorites (user_id, favorite_type, item_id, item_name, item_data)
            VALUES (?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, type);
            pstmt.setInt(3, itemId);
            pstmt.setString(4, itemName);
            pstmt.setString(5, itemData);
            pstmt.executeUpdate();

            updateUserStatistics(userId, type, true);
            return true;
        } catch (SQLException e) {
            System.err.println("Errore nel salvataggio preferito: " + e.getMessage());
            return false;
        }
    }

    public boolean removeFavorite(long userId, String type, int itemId) {
        String sql = "DELETE FROM favorites WHERE user_id = ? AND favorite_type = ? AND item_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, type);
            pstmt.setInt(3, itemId);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                updateUserStatistics(userId, type, false);
            }
            return rows > 0;
        } catch (SQLException e) {
            System.err.println("Errore nella rimozione preferito: " + e.getMessage());
            return false;
        }
    }

    public List<Favorite> getFavorites(long userId, String type) {
        List<Favorite> favorites = new ArrayList<>();
        String sql = type != null ?
                "SELECT * FROM favorites WHERE user_id = ? AND favorite_type = ? ORDER BY saved_date DESC" :
                "SELECT * FROM favorites WHERE user_id = ? ORDER BY saved_date DESC";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            if (type != null) {
                pstmt.setString(2, type);
            }
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                favorites.add(new Favorite(
                        rs.getInt("id"),
                        rs.getLong("user_id"),
                        rs.getString("favorite_type"),
                        rs.getInt("item_id"),
                        rs.getString("item_name"),
                        rs.getString("item_data"),
                        rs.getString("saved_date")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Errore nel recupero preferiti: " + e.getMessage());
        }
        return favorites;
    }

    public void addSearchHistory(long userId, String searchType, String query) {
        String sql = "INSERT INTO search_history (user_id, search_type, search_query) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, searchType);
            pstmt.setString(3, query);
            pstmt.executeUpdate();

            incrementSearchCount(userId, searchType);
        } catch (SQLException e) {
            System.err.println("Errore nell'aggiunta cronologia: " + e.getMessage());
        }
    }

    private void incrementSearchCount(long userId, String searchType) {
        String sql = "UPDATE user_statistics SET total_searches = total_searches + 1, " +
                searchType.toLowerCase() + "_searches = " + searchType.toLowerCase() + "_searches + 1 " +
                "WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore nell'incremento contatori: " + e.getMessage());
        }
    }

    private void updateUserStatistics(long userId, String type, boolean increment) {
        String sql = "UPDATE user_statistics SET total_favorites = total_favorites " +
                (increment ? "+ 1" : "- 1") + " WHERE user_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Errore nell'aggiornamento statistiche: " + e.getMessage());
        }
    }

    public UserStatistics getUserStatistics(long userId) {
        String sql = """
            SELECT us.*, 
                   (SELECT COUNT(*) FROM favorites WHERE user_id = ? AND favorite_type = 'PLAYER') as player_favorites,
                   (SELECT COUNT(*) FROM favorites WHERE user_id = ? AND favorite_type = 'TEAM') as team_favorites
            FROM user_statistics us
            WHERE us.user_id = ?
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setLong(2, userId);
            pstmt.setLong(3, userId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return new UserStatistics(
                        rs.getLong("user_id"),
                        rs.getInt("total_searches"),
                        rs.getInt("player_searches"),
                        rs.getInt("team_searches"),
                        rs.getInt("total_favorites"),
                        rs.getInt("player_favorites"),
                        rs.getInt("team_favorites")
                );
            }
        } catch (SQLException e) {
            System.err.println("Errore nel recupero statistiche: " + e.getMessage());
        }
        return null;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Database chiuso correttamente");
            }
        } catch (SQLException e) {
            System.err.println("Errore nella chiusura del database: " + e.getMessage());
        }
    }

    // Inner classes per i dati
    public record Favorite(int id, long userId, String type, int itemId,
                           String itemName, String itemData, String savedDate) {}

    public record UserStatistics(long userId, int totalSearches, int playerSearches,
                                 int teamSearches, int totalFavorites,
                                 int playerFavorites, int teamFavorites) {}
}