package com.nbabot.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class NBAApiClient {
    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient client;
    private final Gson gson;

    public NBAApiClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    private String makeRequest(String endpoint) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + endpoint)
                .addHeader("x-apisports-key", apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Errore API: " + response.code());
            }
            return response.body() != null ? response.body().string() : null;
        }
    }

    public JsonObject getPlayerById(int playerId) {
        try {
            String response = makeRequest("/players?id=" + playerId);
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nel recupero giocatore: " + e.getMessage());
            return null;
        }
    }

    public JsonObject searchPlayers(String name) {
        try {
            String response = makeRequest("/players?search=" + name);
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nella ricerca giocatori: " + e.getMessage());
            return null;
        }
    }

    public JsonObject getPlayerStatistics(int playerId, int season) {
        try {
            String response = makeRequest("/players/statistics?id=" + playerId + "&season=" + season);
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nel recupero statistiche giocatore: " + e.getMessage());
            return null;
        }
    }

    public JsonObject getTeamById(int teamId) {
        try {
            String response = makeRequest("/teams?id=" + teamId);
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nel recupero team: " + e.getMessage());
            return null;
        }
    }

    public JsonObject searchTeams(String name) {
        try {
            String response = makeRequest("/teams?search=" + name);
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nella ricerca team: " + e.getMessage());
            return null;
        }
    }

    public JsonObject getGames(String date) {
        try {
            String response = makeRequest("/games?date=" + date);
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nel recupero partite: " + e.getMessage());
            return null;
        }
    }

    public JsonObject getLiveGames() {
        try {
            String response = makeRequest("/games?live=all");
            return gson.fromJson(response, JsonObject.class);
        } catch (IOException e) {
            System.err.println("Errore nel recupero partite live: " + e.getMessage());
            return null;
        }
    }
}