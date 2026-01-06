package com.nbabot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nbabot.api.NBAApiClient;
import com.nbabot.database.DatabaseManager;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class NBABot implements LongPollingSingleThreadUpdateConsumer {
    private final TelegramClient telegramClient;
    private final NBAApiClient nbaApi;
    private final DatabaseManager database;
    private final Map<Long, UserSession> userSessions;

    public NBABot(String botToken, NBAApiClient nbaApi, DatabaseManager database) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.nbaApi = nbaApi;
        this.database = database;
        this.userSessions = new HashMap<>();
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextMessage(update);
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
        }
    }

    private void handleTextMessage(Update update) {
        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText();

        database.registerUser(
                userId,
                update.getMessage().getFrom().getFirstName(),
                update.getMessage().getFrom().getLastName(),
                update.getMessage().getFrom().getUserName()
        );
        database.updateLastInteraction(userId);

        if (text.startsWith("/")) {
            handleCommand(chatId, userId, text);
        } else {
            handleUserInput(chatId, userId, text);
        }
    }

    private void handleCommand(long chatId, long userId, String command) {
        switch (command.split(" ")[0]) {
            case "/start" -> sendWelcomeMessage(chatId);
            case "/help" -> sendHelpMessage(chatId);
            case "/player" -> startPlayerSearch(chatId, userId);
            case "/team" -> startTeamSearch(chatId, userId);
            case "/favorites" -> showFavorites(chatId, userId);
            case "/stats" -> showUserStatistics(chatId, userId);
            case "/live" -> showLiveGames(chatId, userId);
            case "/today" -> showTodayGames(chatId, userId);
            default -> sendMessage(chatId, "Comando non riconosciuto. Usa /help per la lista comandi.");
        }
    }

    private void sendWelcomeMessage(long chatId) {
        String welcome = """
            Benvenuto in NBA Stats Bot!
            
            Servizi disponibili:
            - Ricerca statistiche giocatori
            - Informazioni sulle squadre
            - Gestione preferiti
            - Risultati in tempo reale
            
            Usa il menu in basso o scrivi /help per i dettagli.
            """;

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(welcome)
                .replyMarkup(createMainKeyboard())
                .build();

        sendMessage(message);
    }

    private ReplyKeyboardMarkup createMainKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow("Cerca giocatori", "Cerca squadre"))
                .keyboardRow(new KeyboardRow("Partite oggi", "Partite live"))
                .keyboardRow(new KeyboardRow("Preferiti", "Statistiche utente"))
                .keyboardRow(new KeyboardRow("Aiuto"))
                .resizeKeyboard(true)
                .build();
    }

    private void sendHelpMessage(long chatId) {
        String help = """
            GUIDA AI COMANDI
            
            /player - Cerca un giocatore
            /team - Cerca una squadra
            /today - Partite di oggi
            /live - Risultati in tempo reale
            /favorites - Mostra i tuoi preferiti
            /stats - Le tue statistiche di utilizzo
            /help - Mostra questo messaggio
            
            ISTRUZIONI
            Seleziona una categoria e digita il nome desiderato quando richiesto.
            """;

        sendMessage(chatId, help);
    }

    private void startPlayerSearch(long chatId, long userId) {
        userSessions.put(userId, new UserSession("PLAYER_SEARCH"));
        sendMessage(chatId, "Inserisci il nome del giocatore:");
    }

    private void startTeamSearch(long chatId, long userId) {
        userSessions.put(userId, new UserSession("TEAM_SEARCH"));
        sendMessage(chatId, "Inserisci il nome della squadra:");
    }

    private void handleUserInput(long chatId, long userId, String input) {
        UserSession session = userSessions.get(userId);

        if (session == null) {
            switch (input) {
                case "Cerca giocatori" -> startPlayerSearch(chatId, userId);
                case "Cerca squadre" -> startTeamSearch(chatId, userId);
                case "Partite oggi" -> showTodayGames(chatId, userId);
                case "Partite live" -> showLiveGames(chatId, userId);
                case "Preferiti" -> showFavorites(chatId, userId);
                case "Statistiche utente" -> showUserStatistics(chatId, userId);
                case "Aiuto" -> sendHelpMessage(chatId);
                default -> sendMessage(chatId, "Comando non valido. Usa il menu o scrivi /help.");
            }
            return;
        }

        switch (session.state) {
            case "PLAYER_SEARCH" -> searchPlayer(chatId, userId, input);
            case "TEAM_SEARCH" -> searchTeam(chatId, userId, input);
        }
    }

    private void searchPlayer(long chatId, long userId, String name) {
        sendMessage(chatId, "Ricerca in corso...");
        JsonObject response = nbaApi.searchPlayers(name);

        if (response == null || !response.has("response")) {
            sendMessage(chatId, "Errore durante la ricerca. Riprova più tardi.");
            userSessions.remove(userId);
            return;
        }

        JsonArray results = response.getAsJsonArray("response");
        if (results.size() == 0) {
            sendMessage(chatId, "Nessun risultato trovato. Prova a inserire solo il cognome.");
            userSessions.remove(userId);
            return;
        }

        database.addSearchHistory(userId, "player", name);
        showPlayerResults(chatId, userId, results);
        userSessions.remove(userId);
    }

    private void showPlayerResults(long chatId, long userId, JsonArray players) {
        for (int i = 0; i < players.size(); i++) {
            JsonObject player = players.get(i).getAsJsonObject();
            sendPlayerInfo(chatId, userId, player, false);
        }
    }

    private void sendPlayerInfo(long chatId, long userId, JsonObject player, boolean detailed) {
        int playerId = player.get("id").getAsInt();
        String fullName = player.get("firstname").getAsString() + " " + player.get("lastname").getAsString();

        StringBuilder info = new StringBuilder();
        info.append("SCHEDA GIOCATORE: ").append(fullName).append("\n\n");

        JsonObject birth = player.getAsJsonObject("birth");
        if (birth != null && !birth.get("date").isJsonNull()) {
            info.append("Data di nascita: ").append(birth.get("date").getAsString()).append("\n");
        }

        JsonObject height = player.getAsJsonObject("height");
        if (height != null && !height.get("meters").isJsonNull()) {
            info.append("Altezza: ").append(height.get("meters").getAsString()).append(" m\n");
        }

        JsonObject weight = player.getAsJsonObject("weight");
        if (weight != null && !weight.get("kilograms").isJsonNull()) {
            info.append("Peso: ").append(weight.get("kilograms").getAsString()).append(" kg\n");
        }

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("Statistiche 2024")
                                .callbackData("player_stats_" + playerId + "_2024")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("Salva preferito")
                                .callbackData("save_player_" + playerId)
                                .build()
                ))
                .build();

        sendMessage(SendMessage.builder().chatId(chatId).text(info.toString()).replyMarkup(keyboard).build());
    }

    private void searchTeam(long chatId, long userId, String name) {
        sendMessage(chatId, "Ricerca in corso...");
        JsonObject response = nbaApi.searchTeams(name);

        if (response == null || !response.has("response")) {
            sendMessage(chatId, "Servizio momentaneamente non disponibile.");
            userSessions.remove(userId);
            return;
        }

        JsonArray results = response.getAsJsonArray("response");
        if (results.size() == 0) {
            sendMessage(chatId, "Nessuna squadra trovata.");
            userSessions.remove(userId);
            return;
        }

        database.addSearchHistory(userId, "team", name);
        showTeamResults(chatId, userId, results);
        userSessions.remove(userId);
    }

    private void showTeamResults(long chatId, long userId, JsonArray teams) {
        for (JsonElement teamElement : teams) {
            sendTeamInfo(chatId, userId, teamElement.getAsJsonObject());
        }
    }

    private void sendTeamInfo(long chatId, long userId, JsonObject team) {
        int teamId = team.get("id").getAsInt();
        String name = team.get("name").getAsString();
        String city = team.get("city").getAsString();
        String logo = team.get("logo").getAsString();

        StringBuilder info = new StringBuilder();
        info.append("SQUADRA: ").append(name).append("\n");
        info.append("Città: ").append(city).append("\n");

        // Aggiungi conference se disponibile
        if (team.has("leagues") && !team.get("leagues").isJsonNull()) {
            JsonObject leagues = team.getAsJsonObject("leagues");
            if (leagues.has("standard") && !leagues.get("standard").isJsonNull()) {
                JsonObject standard = leagues.getAsJsonObject("standard");

                if (standard.has("conference") && !standard.get("conference").isJsonNull()) {
                    info.append("Conference: ").append(standard.get("conference").getAsString()).append("\n");
                }

                if (standard.has("division") && !standard.get("division").isJsonNull()) {
                    info.append("Division: ").append(standard.get("division").getAsString()).append("\n");
                }
            }
        }

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("Salva preferito")
                                .callbackData("save_team_" + teamId)
                                .build()
                ))
                .build();

        if (logo != null && !logo.isEmpty()) {
            try {
                telegramClient.execute(SendPhoto.builder()
                        .chatId(chatId)
                        .photo(new InputFile(logo))
                        .caption(info.toString())
                        .replyMarkup(keyboard)
                        .build());
            } catch (TelegramApiException e) {
                sendMessage(SendMessage.builder().chatId(chatId).text(info.toString()).replyMarkup(keyboard).build());
            }
        } else {
            sendMessage(SendMessage.builder().chatId(chatId).text(info.toString()).replyMarkup(keyboard).build());
        }
    }

    private void showTodayGames(long chatId, long userId) {
        sendMessage(chatId, "Caricamento partite di oggi...");

        LocalDate today = LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        JsonObject response = nbaApi.getGames(dateStr);

        if (response == null || !response.has("response")) {
            sendMessage(chatId, "Impossibile recuperare i dati delle partite.");
            return;
        }

        JsonArray games = response.getAsJsonArray("response");
        if (games.size() == 0) {
            sendMessage(chatId, "Nessuna partita in programma oggi.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PARTITE DI OGGI - ").append(today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("\n\n");

        for (JsonElement g : games) {
            JsonObject game = g.getAsJsonObject();
            String status = game.get("status").getAsJsonObject().get("short").getAsString();
            String homeTeam = game.getAsJsonObject("teams").getAsJsonObject("home").get("name").getAsString();
            String awayTeam = game.getAsJsonObject("teams").getAsJsonObject("visitors").get("name").getAsString();

            String statusText = getStatusText(status);
            if (statusText != null) {
                sb.append("[").append(statusText).append("] ");
            }

            if (status.equals("NS")) {
                String time = game.get("time").getAsString();
                sb.append(time).append(" - ");
            }

            sb.append(homeTeam).append(" vs ").append(awayTeam);

            if (!status.equals("NS")) {
                int homeScore = game.getAsJsonObject("scores").getAsJsonObject("home").get("points").getAsInt();
                int awayScore = game.getAsJsonObject("scores").getAsJsonObject("visitors").get("points").getAsInt();
                sb.append(String.format("\n   %d - %d", homeScore, awayScore));
            }

            sb.append("\n\n");
        }

        sendMessage(chatId, sb.toString());
    }

    private String getStatusText(String status) {
        return switch (status) {
            case "NS" -> "Da iniziare";
            case "Q1", "Q2", "Q3", "Q4", "OT", "BT", "HT" -> "In corso";
            case "FT", "AOT" -> "Terminata";
            case "POST" -> "Posticipata";
            case "CANC" -> "Cancellata";
            case "SUSP" -> "Sospesa";
            default -> null;
        };
    }

    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        long userId = update.getCallbackQuery().getFrom().getId();

        String[] parts = callbackData.split("_");
        switch (parts[0]) {
            case "save" -> handleSaveFavorite(chatId, userId, parts);
            case "remove" -> handleRemoveFavorite(chatId, userId, parts);
            case "view" -> handleViewFavorite(chatId, userId, parts);
            case "player" -> showPlayerStats(chatId, userId, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        }
    }

    private void handleSaveFavorite(long chatId, long userId, String[] parts) {
        String type = parts[1].toUpperCase();
        int itemId = Integer.parseInt(parts[2]);

        JsonObject response = type.equals("PLAYER") ? nbaApi.getPlayerById(itemId) : nbaApi.getTeamById(itemId);

        if (response != null && response.has("response")) {
            JsonObject item = response.getAsJsonArray("response").get(0).getAsJsonObject();
            String itemName = type.equals("PLAYER")
                    ? item.get("firstname").getAsString() + " " + item.get("lastname").getAsString()
                    : item.get("name").getAsString();

            if (database.saveFavorite(userId, type, itemId, itemName, item.toString())) {
                sendMessage(chatId, itemName + " aggiunto ai preferiti.");
            } else {
                sendMessage(chatId, "Elemento già presente nei preferiti.");
            }
        }
    }

    private void handleRemoveFavorite(long chatId, long userId, String[] parts) {
        if (database.removeFavorite(userId, parts[1].toUpperCase(), Integer.parseInt(parts[2]))) {
            sendMessage(chatId, "Rimosso dai preferiti.");
            showFavorites(chatId, userId);
        }
    }

    private void showFavorites(long chatId, long userId) {
        List<DatabaseManager.Favorite> favorites = database.getFavorites(userId, null);

        if (favorites.isEmpty()) {
            sendMessage(chatId, "La tua lista preferiti è vuota.");
            return;
        }

        sendMessage(chatId, "I TUOI PREFERITI:");

        for (DatabaseManager.Favorite fav : favorites) {
            String label = fav.type().equals("PLAYER") ? "[Giocatore]" : "[Squadra]";
            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("Visualizza")
                                    .callbackData("view_" + fav.type().toLowerCase() + "_" + fav.itemId())
                                    .build(),
                            InlineKeyboardButton.builder()
                                    .text("Rimuovi")
                                    .callbackData("remove_" + fav.type().toLowerCase() + "_" + fav.itemId())
                                    .build()
                    ))
                    .build();

            sendMessage(SendMessage.builder()
                    .chatId(chatId)
                    .text(label + " " + fav.itemName())
                    .replyMarkup(keyboard)
                    .build());
        }
    }

    private void handleViewFavorite(long chatId, long userId, String[] parts) {
        JsonObject response = parts[1].equals("player")
                ? nbaApi.getPlayerById(Integer.parseInt(parts[2]))
                : nbaApi.getTeamById(Integer.parseInt(parts[2]));

        if (response != null && response.has("response")) {
            JsonObject item = response.getAsJsonArray("response").get(0).getAsJsonObject();
            if (parts[1].equals("player")) sendPlayerInfo(chatId, userId, item, true);
            else sendTeamInfo(chatId, userId, item);
        }
    }

    private void showPlayerStats(long chatId, long userId, int playerId, int season) {
        sendMessage(chatId, "Caricamento statistiche...");
        JsonObject response = nbaApi.getPlayerStatistics(playerId, season);

        if (response == null || !response.has("response") || response.getAsJsonArray("response").size() == 0) {
            sendMessage(chatId, "Statistiche non disponibili per la stagione selezionata.");
            return;
        }

        JsonArray stats = response.getAsJsonArray("response");
        double pts = 0, reb = 0, ast = 0;
        int games = stats.size();

        for (JsonElement e : stats) {
            JsonObject s = e.getAsJsonObject();
            pts += s.get("points").isJsonNull() ? 0 : s.get("points").getAsDouble();
            reb += s.get("totReb").isJsonNull() ? 0 : s.get("totReb").getAsDouble();
            ast += s.get("assists").isJsonNull() ? 0 : s.get("assists").getAsDouble();
        }

        String text = String.format("""
            STATISTICHE %d
            
            Partite giocate: %d
            Media Punti: %.1f
            Media Rimbalzi: %.1f
            Media Assist: %.1f
            """, season, games, pts/games, reb/games, ast/games);

        sendMessage(chatId, text);
    }

    private void showUserStatistics(long chatId, long userId) {
        DatabaseManager.UserStatistics stats = database.getUserStatistics(userId);
        if (stats == null) {
            sendMessage(chatId, "Errore nel recupero dati utente.");
            return;
        }

        String text = String.format("""
            LE TUE STATISTICHE
            
            Ricerche totali: %d
            Giocatori cercati: %d
            Squadre cercate: %d
            
            Preferiti salvati: %d
            """, stats.totalSearches(), stats.playerSearches(), stats.teamSearches(), stats.totalFavorites());

        sendMessage(chatId, text);
    }

    private void showLiveGames(long chatId, long userId) {
        sendMessage(chatId, "Verifica partite in corso...");
        JsonObject response = nbaApi.getLiveGames();

        if (response == null || !response.has("response")) {
            sendMessage(chatId, "Impossibile recuperare i dati live.");
            return;
        }

        JsonArray games = response.getAsJsonArray("response");
        if (games.size() == 0) {
            sendMessage(chatId, "Nessuna partita in corso al momento.");
            return;
        }

        StringBuilder sb = new StringBuilder("PARTITE LIVE:\n\n");
        for (JsonElement g : games) {
            JsonObject game = g.getAsJsonObject();
            String homeTeam = game.getAsJsonObject("teams").getAsJsonObject("home").get("name").getAsString();
            String awayTeam = game.getAsJsonObject("teams").getAsJsonObject("visitors").get("name").getAsString();
            int homeScore = game.getAsJsonObject("scores").getAsJsonObject("home").get("points").getAsInt();
            int awayScore = game.getAsJsonObject("scores").getAsJsonObject("visitors").get("points").getAsInt();
            String status = game.get("status").getAsJsonObject().get("short").getAsString();

            sb.append(String.format("[%s] %s %d - %d %s\n\n",
                    status,
                    homeTeam,
                    homeScore,
                    awayScore,
                    awayTeam));
        }
        sendMessage(chatId, sb.toString());
    }

    private void sendMessage(long chatId, String text) {
        sendMessage(SendMessage.builder().chatId(chatId).text(text).build());
    }

    private void sendMessage(SendMessage message) {
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Errore invio: " + e.getMessage());
        }
    }

    private static class UserSession {
        String state;
        UserSession(String state) { this.state = state; }
    }
}