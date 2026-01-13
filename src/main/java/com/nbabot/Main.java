package com.nbabot;

import com.nbabot.api.NBAApiClient;
import com.nbabot.database.DatabaseManager;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.io.File;

public class Main {
    public static void main(String[] args) {
        System.out.println("Avvio NbaBot...");

        try {
            Configurations configs = new Configurations();
            Configuration config = configs.properties(new File("config.properties"));

            String botToken = config.getString("BOT_TOKEN");
            String apiKey = config.getString("API_NBA_KEY");
            String apiBaseUrl = config.getString("API_NBA_BASE_URL");
            String dbPath = config.getString("DB_PATH");


            if (botToken == null || botToken.equals("inserisci_qui_il_tuo_bot_token")) {
                System.err.println("Errore: Bot token non configurato!");
                System.err.println("Modifica il file config.properties con il tuo bot token.");
                System.exit(1);
            }

            if (apiKey == null || apiKey.equals("inserisci_qui_la_tua_api_key")) {
                System.err.println("Errore: API key non configurato!");
                System.err.println("Modifica il file config.properties con la tua API key.");
                System.exit(1);
            }

            System.out.println("Inizializzazione database...");
            DatabaseManager database = new DatabaseManager(dbPath);

            System.out.println("Inizializzazione client API...");
            NBAApiClient nbaApi = new NBAApiClient(apiKey, apiBaseUrl);

            System.out.println("Registrazione bot Telegram...");
            NBABot bot = new NBABot(botToken, nbaApi, database);

            try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
                botsApplication.registerBot(botToken, bot);

                System.out.println("NbaBot avviato con successo!");
                System.out.println("Premi CTRL+C per fermare il bot");

                Thread.currentThread().join();
            }

        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}